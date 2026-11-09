package com.ruoyi.shipyard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.shipyard.ShipyardService.*;

/**
 * Q15: finance corrects a wrong ledger entry by an approved reversal. The original entry stays;
 * an offsetting entry is added and the paid business returns to its unpaid state.
 */
@Service
public class ShipyardReversals {
    private final JdbcTemplate db;
    private final ShipyardAccess access;
    private final ShipyardService service;
    public ShipyardReversals(JdbcTemplate db,ShipyardAccess access,ShipyardService service) { this.db=db; this.access=access; this.service=service; }

    static String permissionFor(String source) {
        switch(source) {
            case "expense": return "expense:pay";
            case "fine": return "fine:handle";
            case "payroll": return "payroll:pay";
            case "manual": case "opening": return "ledger:edit";
            default: return null;
        }
    }
    boolean reviewer() { return access.admin() || access.roles().contains("sy_owner"); }

    @Transactional(isolation=Isolation.READ_COMMITTED) public void request(String ledgerId,Map<String,Object> b) {
        Map<String,Object> entry=service.one("SELECT * FROM sy_ledger WHERE id=? FOR UPDATE",ledgerId);
        access.project(number(entry.get("project_id")));
        String permission=permissionFor(str(entry,"source_kind"));
        check(permission!=null,"冲正流水不能再次冲正");
        access.require(permission);
        String reason=required(b,"reason",500);
        check(db.queryForObject("SELECT COUNT(*) FROM sy_ledger_reversal WHERE ledger_id=? AND status IN ('pending','approved')",Integer.class,ledgerId)==0,"该流水已有冲正申请或已冲正");
        db.update("INSERT INTO sy_ledger_reversal(ledger_id,reason,requested_by) VALUES(?,?,?)",ledgerId,reason,access.uid());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED) public void review(long id,Map<String,Object> b) {
        Map<String,Object> reversal=service.one("SELECT * FROM sy_ledger_reversal WHERE id=? FOR UPDATE",id);
        Map<String,Object> entry=service.one("SELECT * FROM sy_ledger WHERE id=? FOR UPDATE",reversal.get("ledger_id"));
        access.project(number(entry.get("project_id")));
        check(reviewer(),"只有管理员或负责人可以审批冲正");
        if(!"pending".equals(reversal.get("status")) || number(b.get("version"))!=number(reversal.get("version"))) throw new com.ruoyi.common.exception.ServiceException("冲正申请已被处理，请刷新后重试",409);
        check(number(reversal.get("requested_by"))!=access.uid(),"冲正申请人不能审批自己的申请");
        check(b.get("accept") instanceof Boolean,"请选择审批结果");
        boolean accept=Boolean.TRUE.equals(b.get("accept"));
        String opinion=str(b,"opinion"); check(opinion.length()<=500,"审批意见过长");
        if(!accept) {
            check(!opinion.isEmpty(),"驳回冲正必须填写原因");
            db.update("UPDATE sy_ledger_reversal SET status='rejected',opinion=?,reviewed_by=?,reviewed_at=NOW(),version=version+1 WHERE id=?",opinion,access.uid(),id);
            return;
        }
        String reason=str(reversal,"reason"),source=str(entry,"source_kind"),sourceId=str(entry,"source_id").replaceFirst("#[0-9]+$","");
        long pid=number(entry.get("project_id"));
        service.ledger(pid,"冲正："+str(entry,"title"),((BigDecimal)entry.get("amount")).negate(),LocalDate.now().toString(),reason,"reversal",str(entry,"id"));
        if("expense".equals(source)) reopenRecord(sourceId,"报销","已付款","待付款","付款冲正",reason,Arrays.asList("proof","paidDate","method"));
        else if("fine".equals(source)) reopenRecord(sourceId,"罚款","已处理","会计待处理","收款冲正",reason,Arrays.asList("financeNote","paidDate"));
        else if("payroll".equals(source)) {
            Map<String,Object> batch=service.one("SELECT * FROM sy_payroll_batch WHERE id=? FOR UPDATE",sourceId);
            check(batch.get("reversed_at")==null,"该工资批次已冲正");
            List<String> recordIds=db.queryForList("SELECT DISTINCT record_id FROM sy_work_line WHERE payment_id=? ORDER BY record_id",String.class,sourceId);
            List<Map<String,Object>> records=new ArrayList<>();
            for(String recordId:recordIds) records.add(service.raw(recordId,true));
            db.update("UPDATE sy_work_line SET payment_id=NULL WHERE payment_id=?",sourceId);
            db.update("UPDATE sy_payroll_batch SET reversed_at=NOW() WHERE id=?",sourceId);
            for(Map<String,Object> r:records) service.change(r,"待结算","工资冲正","结算批次 "+sourceId+" 冲正："+reason,service.body(r));
        }
        db.update("UPDATE sy_ledger_reversal SET status='approved',opinion=?,reviewed_by=?,reviewed_at=NOW(),version=version+1 WHERE id=?",opinion,access.uid(),id);
    }

    private void reopenRecord(String recordId,String kind,String from,String to,String action,String reason,List<String> fields) {
        Map<String,Object> r=service.raw(recordId,true);
        check(kind.equals(r.get("kind"))&&from.equals(r.get("status")),"原单据状态已变化，不能冲正");
        JSONObject content=service.body(r);
        for(String field:fields) content.remove(field);
        service.change(r,to,action,reason,content);
    }
}
