package com.ruoyi.shipyard;

import java.util.*;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.DuplicateKeyException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.constant.CacheConstants;
import static com.ruoyi.shipyard.ShipyardService.*;

@Service
public class ShipyardAdministration {
    private final JdbcTemplate db;
    private final ShipyardAccess access;
    private final RedisCache redis;
    public ShipyardAdministration(JdbcTemplate db,ShipyardAccess access,RedisCache redis) { this.db=db; this.access=access; this.redis=redis; }
    long insert(String sql,Object... args) {
        KeyHolder key=new GeneratedKeyHolder();
        db.update(c->{java.sql.PreparedStatement p=c.prepareStatement(sql,java.sql.Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]); return p;},key);
        return key.getKey().longValue();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void master(String type,Map<String,Object> b) {
        access.require("master:edit");
        long id=b.get("id")==null?0:number(b.get("id"));
        String name=required(b,"name",50);
        if("crew".equals(type)) {
            saveCrew(b,name);
        } else if("project".equals(type)) {
            String title=required(b,"title",120);
            Object price=decimal(b.get("price"),true),area=decimal(b.get("area"),true),multiplier=java.math.BigDecimal.ONE;
            if(id==0) {
                long project=insert("INSERT INTO sy_project(name,title,price,area,overtime_rate) VALUES(?,?,?,?,?)",name,title,price,area,multiplier);
                if(!access.admin()) db.update("INSERT INTO sy_project_member(project_id,user_id) VALUES(?,?)",project,access.uid());
            }
            else { access.project(id); check(db.update("UPDATE sy_project SET name=?,title=?,price=?,area=?,overtime_rate=? WHERE id=?",name,title,price,area,multiplier,id)==1,"工程不存在"); }
        } else if("area".equals(type)) {
            long pid=number(b.get("projectId")); access.project(pid);
            Object area=decimal(b.get("area"),true),rate=decimal(b.get("rate"),true);
            if(id==0) insert("INSERT INTO sy_area(project_id,name,area,rate) VALUES(?,?,?,?)",pid,name,area,rate);
            else check(db.update("UPDATE sy_area SET name=?,area=?,rate=? WHERE id=? AND project_id=? AND completed_at IS NULL",name,area,rate,id,pid)==1,"区域不存在或已完工");
        } else if("person".equals(type)) {
            String category=ShipyardTeams.category(b);
            Long crewId=b.containsKey("crewId")?ShipyardCrews.id(b.get("crewId")):null;
            Map<String,Object> crew=crewId!=null&&crewId>0?ShipyardCrews.find(db,crewId):null;
            String code=required(b,"code",32),team=crew!=null&&str(b,"team").isEmpty()?str(crew,"name"):required(b,"team",50); Object rate=decimal(b.get("rate"),true);
            Long active=b.containsKey("active")?number(b.get("active")):null;
            check(active==null || active==0 || active==1,"人员状态无效");
            if(id==0) id=insert("INSERT INTO sy_worker(name,code,team,rate,active) VALUES(?,?,?,?,?)",name,code,team,rate,active==null?1:active);
            else {
                Map<String,Object> worker=freezeWorkerHistory(id);
                check(db.update("UPDATE sy_worker SET name=?,code=?,team=?,rate=?,active=? WHERE id=?",name,code,team,rate,active==null?worker.get("active"):active,id)==1,"人员不存在");
            }
            if(category!=null) db.update("UPDATE sy_worker SET team_category=? WHERE id=?",category,id);
            if(crewId!=null) db.update("UPDATE sy_worker SET crew_id=? WHERE id=?",crewId==0?null:crewId,id);
        } else throw new ServiceException("资料类型无效",400);
    }
    private void saveCrew(Map<String,Object> b,String name){
        long id=ShipyardCrews.id(b.get("id"));Object category=b.get("category");
        check(category instanceof String&&Arrays.asList("grinding","painting").contains(category),"班组类型请选择打磨或油漆");
        long leaderId=ShipyardCrews.id(b.get("leaderId"));check(leaderId>0,"请选择班组组长");
        List<Map<String,Object>> leaders=db.queryForList("SELECT DISTINCT u.user_id AS id,u.nick_name AS name FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role r ON r.role_id=ur.role_id WHERE u.user_id=? AND u.status='0' AND u.del_flag='0' AND r.role_key='sy_leader' AND r.status='0' AND r.del_flag='0' FOR UPDATE",leaderId);
        check(leaders.size()==1,"请选择有效的组长账号");
        long version=0;
        if(id>0){Map<String,Object> current=ShipyardCrews.find(db,id);check(b.containsKey("version")&&b.get("version")!=null,"请刷新班组版本");version=ShipyardCrews.id(b.get("version"));if(number(current.get("version"))!=version)throw new ServiceException("班组已被更新，请刷新后重试",409);}
        Integer duplicates=db.queryForObject("SELECT COUNT(*) FROM sy_crew WHERE category=? AND name=? AND id<>?",Integer.class,category,name,id);
        if(duplicates!=null&&duplicates>0)throw new ServiceException("该类型已存在同名班组",409);
        try{
            if(id==0)insert("INSERT INTO sy_crew(name,category,leader_id,leader_name) VALUES(?,?,?,?)",name,category,leaderId,leaders.get(0).get("name"));
            else if(db.update("UPDATE sy_crew SET name=?,category=?,leader_id=?,leader_name=?,version=version+1 WHERE id=? AND version=?",name,category,leaderId,leaders.get(0).get("name"),id,version)!=1)throw new ServiceException("班组已被更新，请刷新后重试",409);
        }catch(DuplicateKeyException e){throw new ServiceException("该类型已存在同名班组",409);}
    }
    /** Lock legacy records before the worker, matching resubmission's record -> worker order. */
    Map<String,Object> freezeWorkerHistory(long workerId) {
        String path="$.workSnapshot.\""+workerId+"\"";
        List<String> candidates=db.queryForList("SELECT r.id FROM sy_record r WHERE (r.kind='罚款' AND JSON_UNQUOTE(JSON_EXTRACT(r.body,'$.person'))=? AND JSON_EXTRACT(r.body,'$.personSnapshot') IS NULL) OR (r.kind='用工' AND JSON_EXTRACT(r.body,?) IS NULL AND EXISTS(SELECT 1 FROM sy_work_line l WHERE l.record_id=r.id AND l.worker_id=?)) ORDER BY r.id",String.class,String.valueOf(workerId),path,workerId);
        List<Map<String,Object>> locked=new ArrayList<>();
        for(String recordId:candidates) locked.addAll(db.queryForList("SELECT * FROM sy_record WHERE id=? FOR UPDATE",recordId));
        List<Map<String,Object>> workers=db.queryForList("SELECT id,name,code,team,rate,active FROM sy_worker WHERE id=? FOR UPDATE",workerId);
        check(workers.size()==1,"人员不存在");Map<String,Object> worker=workers.get(0);
        for(Map<String,Object> record:locked) {
            JSONObject content=JSON.parseObject(String.valueOf(record.get("body")));
            boolean changed=false;
            if("罚款".equals(record.get("kind")) && String.valueOf(workerId).equals(str(content,"person")) && !(content.get("personSnapshot") instanceof Map)) {
                ShipyardSnapshots.rememberPerson(content,worker);changed=true;
            } else if("用工".equals(record.get("kind")) && !(ShipyardSnapshots.work(content).get(String.valueOf(workerId)) instanceof Map)) {
                List<String> names=db.queryForList("SELECT worker_name FROM sy_work_line WHERE record_id=? AND worker_id=?",String.class,record.get("id"),workerId);
                if(!names.isEmpty()) {ShipyardSnapshots.rememberWork(content,worker,names.get(0));changed=true;}
            }
            if(changed) db.update("UPDATE sy_record SET body=? WHERE id=?",content.toJSONString(),record.get("id"));
        }
        return worker;
    }
    public Map<String,Object> accounts() {
        access.require("account:edit");
        List<Map<String,Object>> roles=db.queryForList("SELECT role_id AS id,role_name AS name,role_key AS `key` FROM sys_role WHERE role_key LIKE 'sy_%' AND del_flag='0' ORDER BY role_sort");
        for(Map<String,Object> role:roles) role.put("permissions",db.queryForList("SELECT m.perms FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id=m.menu_id WHERE rm.role_id=?",String.class,role.get("id")));
        List<Map<String,Object>> users=db.queryForList("SELECT u.user_id AS id,u.user_name AS username,u.nick_name AS name,u.status,EXISTS(SELECT 1 FROM sy_wechat_registration w WHERE w.user_id=u.user_id AND w.status='approved') AS wechatBound FROM sys_user u WHERE u.del_flag='0' ORDER BY u.user_id");
        for(Map<String,Object> u:users) {
            List<String> categories=db.queryForList("SELECT team_category FROM sy_user_team WHERE user_id=?",String.class,u.get("id"));
            u.put("teamCategory",categories.isEmpty()?"":categories.get(0));
            u.put("roleIds",db.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=?",Long.class,u.get("id")));
            u.put("projectIds",db.queryForList("SELECT project_id FROM sy_project_member WHERE user_id=?",Long.class,u.get("id")));
        }
        return map("roles",roles,"users",users,"catalog",Arrays.stream(ShipyardBootstrap.PERMISSIONS).map(p->map("name",p[0],"key","shipyard:"+p[1])).collect(java.util.stream.Collectors.toList()));
    }
    @Transactional public void permissions(long roleId,Map<String,Object> b) {
        access.require("account:edit");
        List<String> role=db.queryForList("SELECT role_key FROM sys_role WHERE role_id=? AND role_key LIKE 'sy_%'",String.class,roleId);
        check(!role.isEmpty()&&!"sy_admin".equals(role.get(0)),"管理员权限不可在此修改");
        check(b.get("permissions") instanceof List,"权限列表格式错误");
        List<Long> menuIds=new ArrayList<>();
        for(Object permission:(List<?>)b.get("permissions")) {
            List<Long> menus=db.queryForList("SELECT menu_id FROM sys_menu WHERE perms=? AND perms LIKE 'shipyard:%'",Long.class,permission);
            check(menus.size()==1,"权限标识无效"); menuIds.add(menus.get(0));
        }
        db.update("DELETE FROM sys_role_menu WHERE role_id=?",roleId);
        for(Long menu:new HashSet<>(menuIds)) db.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)",roleId,menu);
    }
    @Transactional public void account(Map<String,Object> b) {
        access.require("account:edit"); long id=b.get("id")==null?0:number(b.get("id"));
        String category=ShipyardTeams.category(b);
        check(id!=1,"超级管理员请使用若依个人资料修改");
        String username=required(b,"username",20),name=required(b,"name",30),status=required(b,"status",1);
        check(username.matches("[A-Za-z0-9_]{3,20}")&&Arrays.asList("0","1").contains(status),"账号或状态无效");
        List<Long> roles=ids(b.get("roleIds"));
        boolean allProjects=false;
        for(Long role:roles) {
            List<String> keys=db.queryForList("SELECT role_key FROM sys_role WHERE role_id=? AND role_key LIKE 'sy_%' AND status='0' AND del_flag='0'",String.class,role);
            check(keys.size()==1,"只能配置有效的船厂业务角色");
            if(Arrays.asList("sy_admin","sy_manager","sy_owner").contains(keys.get(0))) allProjects=true;
        }
        check(b.get("projectIds") instanceof List,"工程范围格式错误");
        List<Long> projects=((List<?>)b.get("projectIds")).stream().map(ShipyardService::number).distinct().collect(java.util.stream.Collectors.toList());
        check(!"0".equals(status) || allProjects || !projects.isEmpty(),"请至少分配一个工程");
        for(Long project:projects) access.project(project);
        String password=str(b,"password"); if(id==0||!password.isEmpty()) check(password.length()>=12&&password.length()<=64&&password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72,"密码长度应为12—64位，UTF-8编码不超过72字节");
        check(db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_name=? AND user_id<>?",Integer.class,username,id)==0,"账号已存在");
        if(id==0) id=insert("INSERT INTO sys_user(dept_id,user_name,nick_name,password,status,del_flag,pwd_update_date,create_by,create_time) VALUES(101,?,?,?,?,'0',NOW(),'shipyard',NOW())",username,name,new BCryptPasswordEncoder().encode(password),status);
        else {
            check(db.update("UPDATE sys_user SET user_name=?,nick_name=?,status=?,update_time=NOW() WHERE user_id=? AND del_flag='0'",username,name,status,id)==1,"账号不存在");
            if(!password.isEmpty()) db.update("UPDATE sys_user SET password=?,pwd_update_date=NOW() WHERE user_id=?",new BCryptPasswordEncoder().encode(password),id);
        }
        db.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        for(Long role:roles) db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);
        db.update("DELETE FROM sy_project_member WHERE user_id=?",id);
        for(Long project:projects) db.update("INSERT INTO sy_project_member(project_id,user_id) VALUES(?,?)",project,id);
        if(category!=null) db.update("INSERT INTO sy_user_team(user_id,team_category) VALUES(?,?) ON DUPLICATE KEY UPDATE team_category=VALUES(team_category)",id,category);
        final long userId=id;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for(String key:redis.keys(CacheConstants.LOGIN_TOKEN_KEY+"*")) {
                    LoginUser login=redis.getCacheObject(key);
                    if(login!=null && login.getUserId()==userId) redis.deleteObject(key);
                }
            }
        });
    }
}
