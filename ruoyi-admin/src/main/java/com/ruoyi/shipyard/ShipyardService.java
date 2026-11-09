package com.ruoyi.shipyard;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class ShipyardService {
    final JdbcTemplate db;
    final ShipyardAccess access;
    public ShipyardService(JdbcTemplate db,ShipyardAccess access) { this.db=db; this.access=access; }
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put(pairs[i].toString(),pairs[i+1]);
        return result;
    }
    static void check(boolean condition,String message) { if(!condition) throw new ServiceException(message,400); }
    static String str(Map<String,?> body,String key) { Object value=body.get(key); return value==null?"":String.valueOf(value).trim(); }
    static String required(Map<String,?> b,String k,int max) { String s=str(b,k); check(!s.isEmpty()&&s.length()<=max,k+"不能为空或超长"); return s; }
    static long number(Object value) { try { return Long.parseLong(String.valueOf(value)); } catch(Exception e) { throw new ServiceException("无效编号",400); } }
    static BigDecimal decimal(Object value,boolean positive) {
        try {
            BigDecimal n=new BigDecimal(String.valueOf(value));
            check(n.scale()<=2 && n.abs().compareTo(new BigDecimal("999999999.99"))<=0 && (positive?n.signum()>0:n.signum()>=0),"金额或工时无效，最多两位小数");
            return n.setScale(2,RoundingMode.UNNECESSARY);
        } catch(NumberFormatException | ArithmeticException e) { throw new ServiceException("金额或工时格式错误",400); }
    }
    static LocalDate date(String value) { try { return LocalDate.parse(value); } catch(Exception e) { throw new ServiceException("日期格式错误",400); } }
    static List<Long> ids(Object value) {
        check(value instanceof List,"请选择人员或明细");
        List<Long> values=((List<?>)value).stream().map(ShipyardService::number).sorted().collect(Collectors.toList());
        check(!values.isEmpty()&&values.size()<=200&&new HashSet<>(values).size()==values.size(),"人员或明细不能为空、重复或超过200项");
        return values;
    }
    static List<Long> payrollIds(Object value) {
        check(value instanceof List,"请选择工资明细");
        List<Long> values=((List<?>)value).stream().map(ShipyardService::number).sorted().collect(Collectors.toList());
        check(!values.isEmpty(),"请选择工资明细");
        check(values.size()<=20000,"单次最多结算20000条工资明细，请缩小结算范围");
        check(new HashSet<>(values).size()==values.size(),"工资明细不能重复");
        return values;
    }
    String name() { return db.queryForObject("SELECT nick_name FROM sys_user WHERE user_id=?",String.class,access.uid()); }
    Map<String,Object> one(String sql,Object... args) {
        List<Map<String,Object>> rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw new ServiceException("记录不存在",404);
        return rows.get(0);
    }
    Map<String,Object> raw(String id,boolean lock) { return one("SELECT * FROM sy_record WHERE id=?"+(lock?" FOR UPDATE":""),id); }
    JSONObject body(Map<String,Object> r) { return JSON.parseObject(String.valueOf(r.get("body"))); }
    void version(Map<String,Object> r,Map<String,Object> request) {
        if(number(request.get("version"))!=number(r.get("version"))) throw new ServiceException("单据已被处理，请刷新后重试",409);
    }
    String requestKey(Map<String,Object> b) { String s=required(b,"requestKey",64); check(s.matches("[a-zA-Z0-9_-]{16,64}"),"请求编号无效"); return s; }
    // Serialize retries by the same account before checking their idempotency key.
    // Call in READ_COMMITTED transactions so a waiting retry sees the committed result.
    void lockRequestOwner() { one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",access.uid()); }
    void audit(String id,String action,String from,String to,String opinion,Object snapshot) {
        db.update("INSERT INTO sy_audit(record_id,actor_id,actor_name,action,from_status,to_status,opinion,snapshot) VALUES(?,?,?,?,?,?,?,?)",id,access.uid(),name(),action,from,to,opinion,JSON.toJSONString(snapshot));
    }
    void change(Map<String,Object> r,String status,String action,String opinion,JSONObject content) {
        db.update("UPDATE sy_record SET status=?,body=?,version=version+1 WHERE id=?",status,content.toJSONString(),r.get("id"));
        audit(str(r,"id"),action,str(r,"status"),status,opinion,content);
    }
    void file(String value,String recordId) {
        check(value.matches("file:[a-f0-9-]{36}"),"请上传真实凭证");
        String id=value.substring(5);
        check(db.queryForObject("SELECT COUNT(*) FROM sy_file WHERE id=? AND owner_id=?",Integer.class,id,access.uid())==1,"不能使用其他账号上传的凭证");
        if(recordId!=null) db.update("INSERT IGNORE INTO sy_record_file(record_id,file_id) VALUES(?,?)",recordId,id);
    }
    List<String> files(Object input) {
        check(input==null || input instanceof List,"凭证格式错误");
        List<String> result=new ArrayList<>();
        if(input!=null) for(Object f:(List<?>)input) { file(String.valueOf(f),null); result.add(String.valueOf(f)); }
        check(result.size()<=6,"最多上传6张凭证"); return result;
    }
    public Map<String,Object> detail(String id) { Map<String,Object> r=raw(id,false); access.record(r); return view(r); }
    Map<String,Object> view(Map<String,Object> r) {
        JSONObject result=body(r);
        result.remove(ShipyardIdempotency.META);
        result.putAll(map("id",r.get("id"),"projectId",r.get("project_id"),"areaId",r.get("area_id"),"kind",r.get("kind"),"type",r.get("type"),"title",r.get("title"),"amount",r.get("amount"),"status",r.get("status"),"version",r.get("version"),"date",String.valueOf(r.get("business_date")),"createdBy",r.get("created_by")));
        result.put("author",db.queryForObject("SELECT nick_name FROM sys_user WHERE user_id=?",String.class,r.get("created_by")));
        result.put("ship",db.queryForObject("SELECT name FROM sy_project WHERE id=?",String.class,r.get("project_id")));
        result.put("time",String.valueOf(r.get("created_at")).substring(11,16));
        result.putIfAbsent("people",new ArrayList<>()); result.putIfAbsent("files",new ArrayList<>());
        List<Map<String,Object>> lines=db.queryForList("SELECT l.id,l.worker_id AS workerId,l.worker_name AS name,l.hours,l.rate,l.multiplier,l.amount,l.payment_id AS paymentId,w.code,w.team FROM sy_work_line l JOIN sy_worker w ON w.id=l.worker_id WHERE l.record_id=? ORDER BY l.worker_id",r.get("id"));
        for(Map<String,Object> line:lines) {
            Map<String,Object> snapshot=ShipyardSnapshots.rememberWork(result,map("id",line.get("workerId"),"name",line.get("name"),"code",line.get("code"),"team",line.get("team")),str(line,"name"));
            line.put("code",snapshot.get("code"));line.put("team",snapshot.get("team"));
        }
        result.put("lines",lines);
        if("罚款".equals(r.get("kind")) && !(result.get("personSnapshot") instanceof Map)) {
            List<Map<String,Object>> workers=db.queryForList("SELECT id,name,code,team FROM sy_worker WHERE id=?",number(result.get("person")));
            if(!workers.isEmpty()) ShipyardSnapshots.rememberPerson(result,workers.get(0));
        }
        result.put("audit",db.queryForList("SELECT id,actor_id AS actorId,actor_name AS actor,action,from_status AS fromStatus,to_status AS status,opinion,created_at AS time FROM sy_audit WHERE record_id=? ORDER BY id",r.get("id")));
        return result;
    }

    /** Worker locks serialize daily-hour checks; READ_COMMITTED prevents an earlier lookup from freezing their SUM snapshot. */
    @Transactional(isolation = Isolation.READ_COMMITTED) public Map<String,Object> submit(Map<String,Object> b,String existing) {
        String kind=required(b,"kind",12);
        check(Arrays.asList("用工","报销","罚款").contains(kind),"申请类型无效");
        access.require("用工".equals(kind)?"work:submit":"报销".equals(kind)?"expense:submit":"fine:submit");
        long pid=number(b.get("projectId")); access.project(pid);
        String key=requestKey(b);
        if(existing==null) {
            lockRequestOwner();
            List<Map<String,Object>> prior=db.queryForList("SELECT * FROM sy_record WHERE created_by=? AND request_key=?",access.uid(),key);
            if(!prior.isEmpty()) {
                Map<String,Object> saved=prior.get(0);
                access.record(saved);
                ShipyardIdempotency.submission(b,saved);
                return view(saved);
            }
        }
        Map<String,Object> old=null;
        if(existing!=null) {
            old=raw(existing,true); access.record(old); version(old,b);
            check(number(old.get("created_by"))==access.uid()&&"已退回".equals(old.get("status")),"仅申请人可修改被退回的单据");
            check(kind.equals(old.get("kind"))&&pid==number(old.get("project_id")),"重新提交不能变更单据类型和工程");
        }
        Map<String,Object> area=null;
        if(!"报销".equals(kind)) {
            area=one("SELECT * FROM sy_area WHERE id=? AND project_id=? FOR UPDATE",number(b.get("areaId")),pid);
            check(area.get("completed_at")==null,"该工作区域已经完工");
            if("用工".equals(kind)) check(area.get("completion_requested_at")==null,"该区域已提交完工，等待项目经理确认；如需继续施工，请项目经理先退回完工申请");
        }
        String type="用工".equals(kind)?required(b,"type",12):"";
        String day=str(b,"date"); if(day.isEmpty()) day=LocalDate.now().toString(); date(day);
        JSONObject content=new JSONObject();
        content.put("note",required(b,"note",500));
        content.put("area",area==null?"":area.get("name"));
        content.put("files",files(b.get("files")));
        BigDecimal amount=BigDecimal.ZERO;
        List<Map<String,Object>> lines=new ArrayList<>();
        String title;
        if("用工".equals(kind)) {
            check(Arrays.asList("包工","点工","加班").contains(type),"施工方式无效");
            List<Long> workers=ids(b.get("people")); content.put("people",workers);
            Map<String,Object> hours=b.get("hours") instanceof Map?(Map<String,Object>)b.get("hours"):Collections.emptyMap();
            Map<String,Object> allocation=b.get("allocations") instanceof Map?(Map<String,Object>)b.get("allocations"):Collections.emptyMap();
            // Overtime uses the worker's normal hourly rate. Preserve this value in the wage snapshot.
            BigDecimal multiplier=BigDecimal.ONE;
            if("包工".equals(type)) {
                String start=required(b,"start",10),end=required(b,"end",10);
                check(!date(end).isBefore(date(start)),"结束日期不能早于开始日期");
                day=start; content.put("start",start); content.put("end",end); content.put("allocations",allocation);
            }
            if("加班".equals(type)) {
                BigDecimal duration=decimal(b.get("duration"),true); check(duration.compareTo(new BigDecimal("16"))<=0,"加班时长不能超过16小时");
                content.put("duration",duration); content.put("hoursTotal",duration.multiply(new BigDecimal(workers.size()))); content.put("multiplier",multiplier);
            }
            content.put("hours",hours);
            for(Long wid:workers) {
                Map<String,Object> worker=one("SELECT * FROM sy_worker WHERE id=? AND active=1 FOR UPDATE",wid);
                ShipyardSnapshots.rememberWork(content,ShipyardCrews.snapshot(db,worker),str(worker,"name"));
                BigDecimal rate=(BigDecimal)worker.get("rate");
                BigDecimal h="包工".equals(type)?BigDecimal.ZERO:decimal("加班".equals(type)?b.get("duration"):hours.get(wid.toString()),true);
                check(h.compareTo(new BigDecimal("24"))<=0,"单日工时不能超过24小时");
                if(!"包工".equals(type)) {
                    BigDecimal used=db.queryForObject("SELECT COALESCE(SUM(l.hours),0) FROM sy_work_line l JOIN sy_record r ON r.id=l.record_id WHERE l.worker_id=? AND r.business_date=? AND r.status<>'已退回' AND r.id<>?",BigDecimal.class,wid,day,existing==null?"":existing);
                    check(used.add(h).compareTo(new BigDecimal("24"))<=0,worker.get("name")+"当日累计工时超过24小时");
                }
                if("加班".equals(type)) check(db.queryForObject("SELECT COUNT(*) FROM sy_work_line l JOIN sy_record r ON r.id=l.record_id WHERE l.worker_id=? AND r.area_id=? AND r.business_date=? AND r.type='点工' AND r.status<>'已退回'",Integer.class,wid,area.get("id"),day)>0,worker.get("name")+"须先在同区域登记当日点工");
                BigDecimal wage="包工".equals(type)?decimal(allocation.get(wid.toString()),true):h.multiply(rate).multiply(multiplier).setScale(2,RoundingMode.HALF_UP);
                amount=amount.add(wage);
                lines.add(map("workerId",wid,"name",worker.get("name"),"hours",h,"rate",rate,"multiplier",multiplier,"amount",wage));
            }
            if("包工".equals(type)) check(amount.compareTo(decimal(b.get("amount"),true))==0,"每人分配金额合计必须等于包工总价");
            title=type+"申请";
        } else {
            amount=decimal(b.get("amount"),true);
            if("报销".equals(kind)) {
                String category=required(b,"category",30); check(Arrays.asList("劳保用品","材料采购","交通差旅","设备维修","其他费用").contains(category),"报销类别无效");
                check(!((List<?>)content.get("files")).isEmpty(),"报销必须上传发票凭证"); content.put("category",category); title=category+"报销";
            } else {
                long worker=number(b.get("person")); Map<String,Object> person=one("SELECT * FROM sy_worker WHERE id=? AND active=1 FOR UPDATE",worker);
                content.put("person",worker);ShipyardSnapshots.rememberPerson(content,ShipyardCrews.snapshot(db,person));title="安全罚款";
            }
        }
        content.put(ShipyardIdempotency.META,old==null?ShipyardIdempotency.identity(b,day):ShipyardIdempotency.original(old));
        String id=existing==null?UUID.randomUUID().toString():existing;
        if(existing==null) db.update("INSERT INTO sy_record(id,project_id,area_id,kind,type,title,amount,status,business_date,created_by,body,request_key) VALUES(?,?,?,?,?,?,?,'待审批',?,?,?,?)",id,pid,area==null?null:area.get("id"),kind,type,title,amount,day,access.uid(),content.toJSONString(),key);
        else {
            db.update("UPDATE sy_record SET area_id=?,type=?,title=?,amount=?,status='待审批',business_date=?,body=?,version=version+1 WHERE id=?",area==null?null:area.get("id"),type,title,amount,day,content.toJSONString(),id);
            db.update("DELETE FROM sy_work_line WHERE record_id=?",id);
        }
        for(Map<String,Object> l:lines) db.update("INSERT INTO sy_work_line(record_id,worker_id,worker_name,hours,rate,multiplier,amount) VALUES(?,?,?,?,?,?,?)",id,l.get("workerId"),l.get("name"),l.get("hours"),l.get("rate"),l.get("multiplier"),l.get("amount"));
        for(String f:(List<String>)content.get("files")) file(f,id);
        audit(id,existing==null?"提交申请":"修改重提",existing==null?"":str(old,"status"),"待审批","",map("form",content,"amount",amount,"lines",lines));
        return view(raw(id,false));
    }
    @Transactional public Map<String,Object> review(String id,Map<String,Object> b) {
        Map<String,Object> r=raw(id,true); access.record(r); version(r,b);
        String status=str(r,"status"),kind=str(r,"kind");
        check("待审批".equals(status)||"待负责人签字".equals(status),"该单据当前不能审批");
        String permission="用工".equals(kind)?"work:approve":"罚款".equals(kind)?"fine:approve":"待负责人签字".equals(status)?"expense:confirm":"expense:approve";
        access.require(permission);
        check(number(r.get("created_by"))!=access.uid(),"申请人不能审批自己的单据");
        JSONObject content=body(r);
        if("待负责人签字".equals(status)) check(number(content.get("managerId"))!=access.uid(),"负责人和项目经理须分别签字");
        check(b.get("accept") instanceof Boolean,"请选择有效的审批结果");
        boolean accept=Boolean.TRUE.equals(b.get("accept"));
        String opinion=str(b,"opinion"); check(opinion.length()<=500,"审批意见过长");
        if(!accept) check(!opinion.isEmpty(),"退回必须填写原因");
        String next="已退回";
        if(accept) {
            if("加班".equals(r.get("type"))) for(Map<String,Object> line:db.queryForList("SELECT worker_id FROM sy_work_line WHERE record_id=?",id)) {
                check(db.queryForObject("SELECT COUNT(*) FROM sy_work_line l JOIN sy_record r ON r.id=l.record_id WHERE l.worker_id=? AND r.area_id=? AND r.business_date=? AND r.type='点工' AND r.status IN ('待结算','已结算')",Integer.class,line.get("worker_id"),r.get("area_id"),r.get("business_date"))>0,"请先审批对应人员的当日点工记录");
            }
            next="用工".equals(kind)?"待结算":"罚款".equals(kind)?"会计待处理":"待负责人签字";
            if("报销".equals(kind)) {
                String signature=required(b,"signature",50); file(signature,id);
                String prefix="待负责人签字".equals(status)?"owner":"manager";
                content.put(prefix+"Signed",true); content.put(prefix+"Id",access.uid()); content.put(prefix+"Name",name()); content.put(prefix+"Signature",signature); content.put(prefix+"At",LocalDateTime.now().toString());
                if("owner".equals(prefix)) next="待付款";
            }
        }
        content.put("opinion",opinion); change(r,next,accept?"审批通过":"退回修改",opinion,content);
        return view(raw(id,false));
    }
    void ledger(long pid,String title,BigDecimal amount,String day,String note,String source,String sourceId) {
        db.update("INSERT INTO sy_ledger(id,project_id,title,amount,business_date,note,source_kind,source_id,created_by) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),pid,title,amount,day,note,source,sourceId,access.uid());
    }
    @Transactional public Map<String,Object> payExpense(String id,Map<String,Object> b) {
        access.require("expense:pay"); Map<String,Object> r=raw(id,true); access.record(r); version(r,b);
        check("报销".equals(r.get("kind"))&&"待付款".equals(r.get("status")),"报销未完成审批或已经付款");
        String proof=required(b,"proof",50); file(proof,id);
        String day=required(b,"date",10); date(day);
        String method=required(b,"method",20); check(Arrays.asList("银行转账","微信转账","现金").contains(method),"付款方式无效");
        JSONObject content=body(r); content.put("proof",proof); content.put("paidDate",day); content.put("method",method);
        ledger(number(r.get("project_id")),str(r,"title"),((BigDecimal)r.get("amount")).negate(),day,method,"expense",id);
        change(r,"已付款","报销付款",method,content); return view(raw(id,false));
    }
    @Transactional public Map<String,Object> handleFine(String id,Map<String,Object> b) {
        access.require("fine:handle"); Map<String,Object> r=raw(id,true); access.record(r); version(r,b);
        check("罚款".equals(r.get("kind"))&&"会计待处理".equals(r.get("status")),"罚款未审批或已处理");
        String note=required(b,"note",500); String day=required(b,"date",10); date(day);
        JSONObject content=body(r); content.put("financeNote",note); content.put("paidDate",day);
        ledger(number(r.get("project_id")),"安全罚款收款",(BigDecimal)r.get("amount"),day,note,"fine",id);
        change(r,"已处理","罚款收款",note,content); return view(raw(id,false));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public BigDecimal payroll(Map<String,Object> b) {
        access.require("payroll:pay"); long pid=number(b.get("projectId")); access.project(pid);
        String month=required(b,"month",7); try { YearMonth.parse(month); } catch(Exception e) { throw new ServiceException("月份格式错误",400); }
        List<Long> selected=payrollIds(b.get("lineIds")); String key=requestKey(b);
        lockRequestOwner();
        List<Map<String,Object>> prior=db.queryForList("SELECT * FROM sy_payroll_batch WHERE request_key=?",key);
        if(!prior.isEmpty()) {
            Map<String,Object> batch=prior.get(0);
            ShipyardIdempotency.payroll(batch,pid,access.uid(),month,selected,
                    db.queryForList("SELECT id FROM sy_work_line WHERE payment_id=? ORDER BY id",Long.class,batch.get("id")));
            return (BigDecimal)batch.get("amount");
        }
        String placeholders=String.join(",",Collections.nCopies(selected.size(),"?"));
        List<String> recordIds=db.queryForList("SELECT DISTINCT record_id FROM sy_work_line WHERE id IN ("+placeholders+") ORDER BY record_id",String.class,selected.toArray());
        for(String id:recordIds) { Map<String,Object> r=raw(id,true); access.record(r); check(number(r.get("project_id"))==pid&&"待结算".equals(r.get("status")),"存在未审批或已结算的用工"); }
        List<Map<String,Object>> rows=db.queryForList("SELECT l.*,r.business_date FROM sy_work_line l JOIN sy_record r ON r.id=l.record_id WHERE l.id IN ("+placeholders+") ORDER BY l.id FOR UPDATE",selected.toArray());
        check(rows.size()==selected.size(),"工资明细不存在");
        BigDecimal total=BigDecimal.ZERO;
        for(Map<String,Object> row:rows) {
            check(row.get("payment_id")==null,"工资已结算，请刷新");
            check(str(row,"business_date").startsWith(month),"结算月份和工资明细不一致");
            total=total.add((BigDecimal)row.get("amount"));
        }
        String batch=UUID.randomUUID().toString();
        db.update("INSERT INTO sy_payroll_batch(id,project_id,month,amount,paid_by,request_key) VALUES(?,?,?,?,?,?)",batch,pid,month,total,access.uid(),key);
        for(Long line:selected) db.update("UPDATE sy_work_line SET payment_id=? WHERE id=? AND payment_id IS NULL",batch,line);
        ledger(pid,"工资结算",total.negate(),LocalDate.now().toString(),month+" · "+rows.size()+"条工资明细","payroll",batch);
        for(String id:recordIds) {
            Map<String,Object> r=raw(id,false); String next=db.queryForObject("SELECT COUNT(*) FROM sy_work_line WHERE record_id=? AND payment_id IS NULL",Integer.class,id)==0?"已结算":"待结算";
            change(r,next,"工资结算","结算批次 "+batch,body(r));
        }
        return total;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED) public void manualLedger(Map<String,Object> b) {
        access.require("ledger:edit"); long pid=number(b.get("projectId")); access.project(pid);
        String key=requestKey(b),title=required(b,"title",100),day=required(b,"date",10); date(day);
        check(!Arrays.asList("工资结算","安全罚款收款").contains(title),"请通过对应业务流程登记");
        BigDecimal signed; try { signed=new BigDecimal(str(b,"amount")); } catch(Exception e) { throw new ServiceException("金额格式错误",400); }
        BigDecimal amount=decimal(signed.abs(),true).multiply(new BigDecimal(signed.signum()));
        String note=str(b,"note"); check(note.length()<=500,"备注过长");
        lockRequestOwner();
        List<Map<String,Object>> prior=db.queryForList("SELECT project_id,created_by,title,amount,business_date,note FROM sy_ledger WHERE source_kind='manual' AND source_id=?",key);
        if(prior.isEmpty()) ledger(pid,title,amount,day,note,"manual",key);
        else ShipyardIdempotency.ledger(prior.get(0),pid,access.uid(),title,amount,day,note);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void complete(long areaId) {
        access.require("work:complete"); Map<String,Object> area=one("SELECT * FROM sy_area WHERE id=? FOR UPDATE",areaId); access.project(number(area.get("project_id")));
        check(area.get("completed_at")==null,"该区域已完工");
        check(area.get("completion_requested_by")==null||number(area.get("completion_requested_by"))!=access.uid(),"不能确认自己提交的完工申请");
        // The area lock serializes submissions. Locking records here would invert resubmission's record -> area order.
        // READ_COMMITTED sees submissions committed while this request was waiting for the area lock.
        List<Map<String,Object>> current=db.queryForList("SELECT * FROM sy_record WHERE area_id=? ORDER BY id",areaId);
        check(current.stream().noneMatch(r->Arrays.asList("待审批","已退回").contains(r.get("status"))),"该区域仍有待审批或退回单据");
        List<Map<String,Object>> records=current.stream().filter(r->"用工".equals(r.get("kind"))&&Arrays.asList("待结算","已结算").contains(r.get("status"))).collect(Collectors.toList());
        check(!records.isEmpty(),"该区域没有已审批的用工记录");
        db.update("UPDATE sy_area SET completed_at=NOW() WHERE id=?",areaId);
        for(Map<String,Object> r:records) audit(str(r,"id"),"施工完工",str(r,"status"),str(r,"status"),"施工完成，财务结算独立处理",body(r));
    }
    /** A leader asks the project manager to confirm completion; the area is archived only by complete(). */
    @Transactional(isolation=Isolation.READ_COMMITTED) public void requestCompletion(long areaId) {
        access.require("work:submit"); Map<String,Object> area=one("SELECT * FROM sy_area WHERE id=? FOR UPDATE",areaId); access.project(number(area.get("project_id")));
        check(area.get("completed_at")==null,"该区域已完工");
        check(area.get("completion_requested_at")==null,"该区域已提交完工，等待项目经理确认");
        List<Map<String,Object>> current=db.queryForList("SELECT * FROM sy_record WHERE area_id=? AND kind='用工' ORDER BY id",areaId);
        check(current.stream().noneMatch(r->Arrays.asList("待审批","已退回").contains(r.get("status"))),"该区域仍有待审批或退回的用工单据，处理后再提交完工");
        List<Map<String,Object>> own=current.stream().filter(r->number(r.get("created_by"))==access.uid()&&Arrays.asList("待结算","已结算").contains(r.get("status"))).collect(Collectors.toList());
        check(!own.isEmpty(),"您在该区域没有已审批的用工记录，不能提交完工");
        db.update("UPDATE sy_area SET completion_requested_at=NOW(3),completion_requested_by=? WHERE id=?",access.uid(),areaId);
        // Audit rows on the requester's work records feed the in-app messages of reviewers.
        for(Map<String,Object> r:own) audit(str(r,"id"),"提交完工",str(r,"status"),str(r,"status"),"申请确认"+area.get("name")+"施工完工",body(r));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void rejectCompletion(long areaId,Map<String,Object> b) {
        access.require("work:complete"); Map<String,Object> area=one("SELECT * FROM sy_area WHERE id=? FOR UPDATE",areaId); access.project(number(area.get("project_id")));
        String reason=required(b,"reason",200);
        check(area.get("completed_at")==null,"该区域已完工");
        check(area.get("completion_requested_at")!=null,"该区域没有待确认的完工申请");
        long requester=number(area.get("completion_requested_by"));
        db.update("UPDATE sy_area SET completion_requested_at=NULL,completion_requested_by=NULL WHERE id=?",areaId);
        for(Map<String,Object> r:db.queryForList("SELECT * FROM sy_record WHERE area_id=? AND kind='用工' AND created_by=? AND status IN ('待结算','已结算') ORDER BY id",areaId,requester))
            audit(str(r,"id"),"完工退回",str(r,"status"),str(r,"status"),reason,body(r));
    }

    public Map<String,Object> bootstrap() {
        access.member();
        Set<String> grants=access.permissions();
        boolean admin=grants.contains("*:*:*"), allProjects=access.allProjects(), ownOnly=access.ownWorkOnly();
        String ownFilter=ownOnly?" AND created_by="+access.uid():"";
        String ownRecordFilter=ownOnly?" AND r.created_by="+access.uid():"";
        List<Map<String,Object>> projects=db.queryForList("SELECT id,name,title,price,area,1 AS overtimeRate FROM sy_project"+(allProjects?"":" WHERE id IN (SELECT project_id FROM sy_project_member WHERE user_id="+access.uid()+")")+" ORDER BY id");
        List<Map<String,Object>> areas=new ArrayList<>(),records=new ArrayList<>(),transactions=new ArrayList<>(),payroll=new ArrayList<>(),attendance=new ArrayList<>();
        boolean finance=admin||grants.contains("shipyard:finance:view")||grants.contains("shipyard:payroll:pay");
        boolean ledgerEditor=grants.contains("shipyard:ledger:edit");
        for(Map<String,Object> project:projects) {
            long pid=number(project.get("id"));
            List<Map<String,Object>> rawRecords=db.queryForList("SELECT * FROM sy_record WHERE project_id=? ORDER BY created_at DESC",pid);
            for(Map<String,Object> r:rawRecords) if(access.canReadAuthorizedProject(r,grants)) records.add(view(r));
            List<Map<String,Object>> pa=db.queryForList("SELECT a.id,a.project_id AS projectId,a.name,a.area,a.rate,a.completed_at AS completedAt,DATE_FORMAT(a.completion_requested_at,'%Y-%m-%d %H:%i') AS completionRequestedAt,a.completion_requested_by AS completionRequestedBy,u.nick_name AS completionRequester FROM sy_area a LEFT JOIN sys_user u ON u.user_id=a.completion_requested_by WHERE a.project_id=? ORDER BY a.id",pid);
            for(Map<String,Object> a:pa) {
                a.put("id",String.valueOf(a.get("id"))); a.put("ship",project.get("name"));
                boolean started=db.queryForObject("SELECT COUNT(*) FROM sy_record WHERE area_id=? AND kind='用工'",Integer.class,a.get("id"))>0;
                a.put("status",a.get("completedAt")!=null?"已完工":a.get("completionRequestedAt")!=null?"待确认完工":started?"进行中":"未开工");
                List<Map<String,Object>> workers=db.queryForList("SELECT l.worker_id AS id,MAX(l.worker_name) AS name FROM sy_work_line l JOIN sy_record r ON r.id=l.record_id WHERE r.area_id=? AND r.status<>'已退回'"+ownRecordFilter+" GROUP BY l.worker_id ORDER BY l.worker_id",a.get("id"));
                a.put("workers",workers); a.put("people",workers.size());
                a.put("contract",db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM sy_record WHERE area_id=? AND type='包工' AND status IN ('待结算','已结算')"+ownFilter,BigDecimal.class,a.get("id")));
                a.put("wages",db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM sy_record WHERE area_id=? AND type IN ('点工','加班') AND status IN ('待结算','已结算')"+ownFilter,BigDecimal.class,a.get("id")));
                a.put("type",((BigDecimal)a.get("contract")).signum()>0?"包工":"点工"); a.put("leader","");
                a.put("paid",db.queryForObject("SELECT COUNT(*) FROM sy_record WHERE area_id=? AND kind='用工' AND status='待结算'"+ownFilter,Integer.class,a.get("id"))==0);
                areas.add(a);
            }
            // A ledger editor can verify their own manual entries without gaining access to payroll or all project finances.
            if(!finance && ledgerEditor) transactions.addAll(db.queryForList("SELECT l.id,l.project_id AS projectId,p.name AS ship,l.title,l.amount,DATE_FORMAT(l.business_date,'%Y-%m-%d') AS date,DATE_FORMAT(l.created_at,'%H:%i') AS time,l.note,l.source_kind AS source,l.source_id AS sourceId FROM sy_ledger l JOIN sy_project p ON p.id=l.project_id WHERE l.project_id=? AND l.created_by=? AND l.source_kind='manual' ORDER BY l.business_date DESC,l.created_at DESC",pid,access.uid()));
            if(finance) {
                payroll.addAll(db.queryForList("SELECT l.id,l.worker_id AS workerId,l.worker_name AS name,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.body,CONCAT('$.workSnapshot.\"',l.worker_id,'\".code'))),w.code) AS code,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.body,CONCAT('$.workSnapshot.\"',l.worker_id,'\".team'))),w.team) AS team,l.amount,l.hours,l.rate,l.multiplier,l.payment_id AS paymentId,r.type AS label,r.project_id AS projectId,DATE_FORMAT(r.business_date,'%Y-%m') AS month,DATE_FORMAT(r.business_date,'%Y-%m-%d') AS date,p.name AS ship FROM sy_work_line l JOIN sy_record r ON r.id=l.record_id JOIN sy_project p ON p.id=r.project_id LEFT JOIN sy_worker w ON w.id=l.worker_id WHERE r.project_id=? AND r.kind='用工' AND r.status IN ('待结算','已结算') ORDER BY r.business_date,l.id",pid));
                transactions.addAll(db.queryForList("SELECT l.id,l.project_id AS projectId,p.name AS ship,l.title,l.amount,DATE_FORMAT(l.business_date,'%Y-%m-%d') AS date,DATE_FORMAT(l.created_at,'%H:%i') AS time,l.note,l.source_kind AS source,l.source_id AS sourceId FROM sy_ledger l JOIN sy_project p ON p.id=l.project_id WHERE l.project_id=? ORDER BY l.business_date DESC,l.created_at DESC",pid));
                project.put("wages",db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM sy_record WHERE project_id=? AND kind='用工' AND status IN ('待结算','已结算')",BigDecimal.class,pid));
                project.put("reimbursements",db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM sy_record WHERE project_id=? AND kind='报销' AND status IN ('待付款','已付款')",BigDecimal.class,pid));
                project.put("otherCosts",db.queryForObject("SELECT COALESCE(-SUM(amount),0) FROM sy_ledger WHERE project_id=? AND source_kind='manual' AND amount<0",BigDecimal.class,pid));
                attendance.addAll(db.queryForList("SELECT areaId,projectId,date,workerId,name,code,team,SUM(hours) AS hours FROM (SELECT r.area_id AS areaId,r.project_id AS projectId,r.business_date AS date,l.worker_id AS workerId,l.worker_name AS name,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.body,CONCAT('$.workSnapshot.\"',l.worker_id,'\".code'))),w.code) AS code,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.body,CONCAT('$.workSnapshot.\"',l.worker_id,'\".team'))),w.team) AS team,l.hours FROM sy_work_line l JOIN sy_record r ON r.id=l.record_id JOIN sy_worker w ON w.id=l.worker_id WHERE r.project_id=? AND r.type IN ('点工','加班') AND r.status IN ('待结算','已结算')) snapshots GROUP BY areaId,projectId,date,workerId,name,code,team",pid));
            }
        }
        for(Map<String,Object> line:payroll) line.put("paid",line.get("paymentId")!=null);
        List<Map<String,Object>> notices=new ArrayList<>();
        Set<String> visible=records.stream().map(r->str(r,"id")).collect(Collectors.toSet());
        for(Map<String,Object> a:db.queryForList("SELECT a.id,a.record_id AS recordId,a.action AS title,a.opinion AS detail,a.created_at AS time,(n.audit_id IS NOT NULL) AS `read` FROM sy_audit a LEFT JOIN sy_notice_read n ON n.audit_id=a.id AND n.user_id=? ORDER BY a.id DESC",access.uid())) if(visible.contains(str(a,"recordId"))) notices.add(a);
        Map<String,Object> user=one("SELECT user_id AS id,user_name AS username,nick_name AS name FROM sys_user WHERE user_id=?",access.uid());
        List<String> userTeams=db.queryForList("SELECT team_category FROM sy_user_team WHERE user_id=?",String.class,access.uid());
        user.put("teamCategory",userTeams.isEmpty()?"":userTeams.get(0));
        List<Map<String,Object>> crews=ShipyardCrews.list(db);
        return map("crews",crews,"crewLeaders",admin||grants.contains("shipyard:master:edit")?ShipyardCrews.leaders(db):Collections.emptyList(),"user",user,"roles",access.uid()==1?Collections.singletonList("admin"):access.roles(),"grants",grants,"projects",projects,"areas",areas,"people",ShipyardCrews.decorate(db.queryForList("SELECT id,name,code,team,team_category AS teamCategory,crew_id AS crewId,rate,active FROM sy_worker WHERE active=1 ORDER BY id"),crews),"workerArchive",admin||grants.contains("shipyard:master:edit")?ShipyardCrews.decorate(db.queryForList("SELECT id,name,code,team,team_category AS teamCategory,crew_id AS crewId,rate,active FROM sy_worker ORDER BY active DESC,id"),crews):Collections.emptyList(),"records",records,"payroll",payroll,"monthlyPayroll",ShipyardMonthlyPayroll.build(payroll),"transactions",transactions,"attendance",attendance,"notifications",notices,"serverDate",LocalDate.now().toString());
    }
    @Transactional public void readNotice(long id) {
        Map<String,Object> a=one("SELECT record_id FROM sy_audit WHERE id=?",id); access.record(raw(str(a,"record_id"),false));
        db.update("INSERT IGNORE INTO sy_notice_read(user_id,audit_id) VALUES(?,?)",access.uid(),id);
    }
}
