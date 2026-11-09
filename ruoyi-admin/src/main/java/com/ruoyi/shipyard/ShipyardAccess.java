package com.ruoyi.shipyard;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

/** Always consult current database permissions, including disabled accounts and revoked roles. */
@Component
public class ShipyardAccess {
    private final JdbcTemplate db;
    public ShipyardAccess(JdbcTemplate db) { this.db = db; }
    public long uid() { return SecurityUtils.getUserId(); }
    public void active() {
        if (db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'", Integer.class, uid()) != 1)
            throw new ServiceException("账号已停用，请重新登录", 401);
    }
    public boolean admin() { active(); return uid() == 1 || roles().contains("sy_admin"); }
    public boolean allProjects() { return uid()==1 || roles().stream().anyMatch(r->Arrays.asList("sy_admin","sy_manager","sy_owner").contains(r)); }
    public boolean ownWorkOnly() { return roles().contains("sy_leader") && !allProjects(); }
    public void member() { active(); if(uid()!=1 && roles().stream().noneMatch(r->r.startsWith("sy_"))) throw new ServiceException("账号未分配船厂角色",403); }
    public List<String> roles() {
        active();
        return db.queryForList("SELECT r.role_key FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.role_id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0'", String.class, uid());
    }
    public Set<String> permissions() {
        // A menu grant alone must not admit an account without an active shipyard role.
        member();
        if (admin()) return new HashSet<>(Collections.singletonList("*:*:*"));
        Set<String> grants=new HashSet<>(db.queryForList("SELECT DISTINCT m.perms FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id=m.menu_id JOIN sys_role r ON r.role_id=rm.role_id JOIN sys_user_role ur ON ur.role_id=r.role_id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms LIKE 'shipyard:%'", String.class, uid()));
        if(allProjects()) grants.add("shipyard:finance:view");
        if(ownWorkOnly()) grants.removeAll(Arrays.asList("shipyard:finance:view","shipyard:payroll:pay"));
        return grants;
    }
    public boolean can(String permission) { Set<String> p=permissions(); return p.contains("*:*:*") || p.contains("shipyard:"+permission); }
    public void require(String permission) { if (!can(permission)) throw new ServiceException("没有该操作权限",403); }
    public void project(long id) {
        member();
        if (db.queryForObject("SELECT COUNT(*) FROM sy_project WHERE id=?",Integer.class,id)==0) throw new ServiceException("工程不存在",404);
        if (!allProjects() && db.queryForObject("SELECT COUNT(*) FROM sy_project_member WHERE project_id=? AND user_id=?",Integer.class,id,uid())==0)
            throw new ServiceException("没有该工程的数据权限",403);
    }
    public boolean canRead(Map<String,Object> r) {
        project(((Number)r.get("project_id")).longValue());
        return canReadAuthorizedProject(r, permissions());
    }
    /** Only call after project authorization. Grants are a local snapshot for this read request, never cached across requests. */
    boolean canReadAuthorizedProject(Map<String,Object> r, Set<String> grants) {
        if (((Number)r.get("created_by")).longValue()==uid() || grants.contains("*:*:*")) return true;
        if(ownWorkOnly()) return false;
        if(allProjects()) return true;
        String kind=String.valueOf(r.get("kind"));
        if(grants.contains("shipyard:finance:view")) return true;
        return "用工".equals(kind) ? grants.contains("shipyard:work:approve") || grants.contains("shipyard:work:complete") || grants.contains("shipyard:payroll:pay")
            : "报销".equals(kind) ? grants.contains("shipyard:expense:approve") || grants.contains("shipyard:expense:confirm") || grants.contains("shipyard:expense:pay")
            : grants.contains("shipyard:fine:approve") || grants.contains("shipyard:fine:handle");
    }
    public void record(Map<String,Object> r) { if(!canRead(r)) throw new ServiceException("无权查看此单据",403); }
}
