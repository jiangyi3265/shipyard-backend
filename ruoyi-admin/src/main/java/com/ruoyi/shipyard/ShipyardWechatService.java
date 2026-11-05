package com.ruoyi.shipyard;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.system.service.ISysUserService;
import static com.ruoyi.shipyard.ShipyardService.*;

@Service
public class ShipyardWechatService {
    private static final String TICKET="shipyard:wechat:ticket:";
    private final JdbcTemplate db;private final RedisCache redis;private final ShipyardWechatClient client;
    private final ShipyardAccess access;private final ShipyardAdministration admin;
    private final ISysUserService users;private final SysPermissionService permissions;
    private final TokenService tokens;private final SysLoginService loginService;
    public ShipyardWechatService(JdbcTemplate db,RedisCache redis,ShipyardWechatClient client,ShipyardAccess access,ShipyardAdministration admin,ISysUserService users,SysPermissionService permissions,TokenService tokens,SysLoginService loginService) {
        this.db=db;this.redis=redis;this.client=client;this.access=access;this.admin=admin;this.users=users;this.permissions=permissions;this.tokens=tokens;this.loginService=loginService;
    }
    public void limit(String source) {
        String key="shipyard:wechat:limit:"+source;
        Long count=redis.redisTemplate.opsForValue().increment(key);
        if(count!=null && count==1) redis.expire(key,60);
        if(count!=null && count>120) throw new ServiceException("操作过于频繁，请稍后重试",429);
    }
    private Map<String,Object> registration(String openId,boolean lock) {
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM sy_wechat_registration WHERE app_id=? AND open_id=?"+(lock?" FOR UPDATE":""),client.appId(),openId);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> login(String code) {
        String openId=client.openId(code);
        Map<String,Object> row=registration(openId,false);
        if(row!=null && "approved".equals(row.get("status"))) {
            long id=((Number)row.get("user_id")).longValue();
            SysUser user=users.selectUserById(id);
            if(user==null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) return map("status","disabled","message","账号已停用，请联系管理员");
            if(db.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=? AND r.role_key LIKE 'sy_%' AND r.status='0' AND r.del_flag='0'",Integer.class,id)==0 && id!=1)
                return map("status","disabled","message","账号尚未分配有效角色，请联系管理员");
            LoginUser login=new LoginUser(user.getUserId(),user.getDeptId(),user,permissions.getMenuPermission(user));
            loginService.recordLoginInfo(id);
            return map("status","approved","token",tokens.createToken(login));
        }
        String ticket=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        redis.setCacheObject(TICKET+ticket,client.appId()+"|"+openId,20,TimeUnit.MINUTES);
        Map<String,Object> result=map("status",row==null?"new":row.get("status"),"ticket",ticket);
        if(row!=null) result.putAll(map("name",row.get("real_name"),"phone",row.get("phone"),"note",row.get("note"),"reason",row.get("review_reason")));
        return result;
    }
    private String identity(Map<String,Object> body) {
        String ticket=required(body,"ticket",64);check(ticket.matches("[a-f0-9]{64}"),"微信身份已过期，请重新点击微信登录");
        String identity=redis.getCacheObject(TICKET+ticket);
        check(identity!=null && identity.startsWith(client.appId()+"|"),"微信身份已过期，请重新点击微信登录");
        return identity.substring(client.appId().length()+1);
    }
    @Transactional public Map<String,Object> apply(Map<String,Object> body) {
        String openId=identity(body),name=required(body,"name",30),phone=str(body,"phone"),note=str(body,"note");
        check(phone.length()<=24 && (phone.isEmpty() || phone.matches("[+0-9 ()-]{6,24}")),"联系电话格式无效");check(note.length()<=300,"申请说明不能超过300字");
        db.update("INSERT INTO sy_wechat_registration(app_id,open_id,real_name,phone,note) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",client.appId(),openId,name,phone,note);
        Map<String,Object> row=registration(openId,true);
        if("approved".equals(row.get("status"))) throw new ServiceException("申请已通过，请重新点击微信登录",409);
        if("rejected".equals(row.get("status"))) db.update("UPDATE sy_wechat_registration SET real_name=?,phone=?,note=?,status='pending',review_reason='',reviewed_by=NULL,reviewed_at=NULL,version=version+1 WHERE id=?",name,phone,note,row.get("id"));
        return map("status","pending","message","申请已提交，等待管理员审核并分配角色和工程");
    }
    public List<Map<String,Object>> registrations() {
        access.require("account:edit");
        return db.queryForList("SELECT w.id,w.real_name AS name,w.phone,w.note,w.status,w.user_id AS userId,u.user_name AS username,w.review_reason AS reason,w.version,w.created_at AS createdAt,w.reviewed_at AS reviewedAt,r.nick_name AS reviewer FROM sy_wechat_registration w LEFT JOIN sys_user u ON u.user_id=w.user_id LEFT JOIN sys_user r ON r.user_id=w.reviewed_by ORDER BY CASE WHEN w.status='pending' THEN 0 ELSE 1 END,w.created_at DESC LIMIT 500");
    }
    @Transactional public void review(long id,Map<String,Object> body) {
        access.require("account:edit");
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM sy_wechat_registration WHERE id=? FOR UPDATE",id);
        check(rows.size()==1,"申请不存在");Map<String,Object> row=rows.get(0);
        if(!"pending".equals(row.get("status")) || number(body.get("version"))!=((Number)row.get("version")).longValue()) throw new ServiceException("申请已被处理，请刷新后重试",409);
        check(body.get("accept") instanceof Boolean,"请选择审核结果");
        if(!Boolean.TRUE.equals(body.get("accept"))) {
            String reason=required(body,"reason",300);
            db.update("UPDATE sy_wechat_registration SET status='rejected',review_reason=?,reviewed_by=?,reviewed_at=NOW(),version=version+1 WHERE id=?",reason,access.uid(),id);return;
        }
        List<Long> roleIds=ids(body.get("roleIds"));check(!roleIds.isEmpty(),"请至少分配一个角色");
        check(body.get("projectIds") instanceof List,"请选择工程范围");
        List<Long> projectIds=new ArrayList<>();for(Object project:(List<?>)body.get("projectIds")) projectIds.add(number(project));
        check(projectIds.size()<=200 && new HashSet<>(projectIds).size()==projectIds.size(),"工程范围不能重复或超过200项");
        boolean all=roleIds.stream().anyMatch(role->db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_id=? AND role_key IN ('sy_admin','sy_manager','sy_owner') AND status='0' AND del_flag='0'",Integer.class,role)>0);
        check(all || !projectIds.isEmpty(),"请至少分配一个工程");
        long userId=body.get("userId")==null?0:number(body.get("userId"));String username,nickname;
        if(userId!=0) {
            check(userId!=1,"超级管理员微信绑定须另行验证，不能通过注册审核关联");
            List<Map<String,Object>> existing=db.queryForList("SELECT user_name,nick_name FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",userId);
            check(existing.size()==1,"关联账号不存在或已停用");
            check(db.queryForList("SELECT id FROM sy_wechat_registration WHERE user_id=? FOR UPDATE",userId).isEmpty(),"该账号已经绑定其他微信");
            username=String.valueOf(existing.get(0).get("user_name"));nickname=String.valueOf(existing.get(0).get("nick_name"));
        } else { username="wx_"+UUID.randomUUID().toString().replace("-","").substring(0,12);nickname=String.valueOf(row.get("real_name")); }
        Map<String,Object> account=map("username",username,"name",nickname,"status","0","password",userId==0?UUID.randomUUID().toString():"","roleIds",roleIds,"projectIds",projectIds);
        if(userId!=0) account.put("id",userId);
        admin.account(account);
        if(userId==0) userId=db.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
        db.update("UPDATE sy_wechat_registration SET status='approved',user_id=?,review_reason='',reviewed_by=?,reviewed_at=NOW(),version=version+1 WHERE id=?",userId,access.uid(),id);
    }
}
