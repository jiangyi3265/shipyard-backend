package com.ruoyi.shipyard;

import java.util.*;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** In-memory SQL boundary only: executes real Access, WechatService and Administration, never production. */
public class ShipyardBusinessAdminRegistrationTest {
    private static final long ACTOR=41006L, CREATED=42001L, EXISTING=42002L, APPLICATION=51L, PROJECT=9L;
    private FixtureDb db;
    private ShipyardWechatService wechat;

    @Before public void setup() {
        SysUser user=new SysUser();user.setUserId(ACTOR);user.setUserName("fixture_business_admin");
        LoginUser login=new LoginUser(ACTOR,101L,user,Collections.emptySet());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,Collections.emptyList()));
        TransactionSynchronizationManager.initSynchronization();
        db=new FixtureDb();RedisCache redis=mock(RedisCache.class);
        when(redis.keys(anyString())).thenReturn(Collections.emptySet());
        ShipyardAccess access=new ShipyardAccess(db);
        ShipyardAdministration administration=new ShipyardAdministration(db,access,redis);
        wechat=new ShipyardWechatService(db,redis,null,access,administration,null,null,null,null);
    }
    @After public void cleanup() {
        TransactionSynchronizationManager.clearSynchronization();SecurityContextHolder.clearContext();
    }
    private Map<String,Object> grant(List<Long> roles,List<Long> projects) {
        return map("accept",true,"version",0,"roleIds",roles,"projectIds",projects,"userId",null);
    }
    private void rejected(int code,String part,Runnable action) {
        try {action.run();fail("Expected rejection");}
        catch(ServiceException expected) {assertEquals(Integer.valueOf(code),expected.getCode());assertTrue(expected.getMessage().contains(part));}
        assertEquals("pending",db.registration.get("status"));assertEquals(0,db.accountInsertCount);
    }

    @Test public void ordinaryBusinessAdminCreatesAndBindsLeaderWithoutNativeSystemGrantsOrProjectMembership() {
        wechat.review(APPLICATION,grant(Arrays.asList(41001L),Arrays.asList(PROJECT)));
        assertEquals("approved",db.registration.get("status"));assertEquals(CREATED,db.registration.get("user_id"));
        assertEquals(ACTOR,db.registration.get("reviewed_by"));assertEquals(1,db.accountInsertCount);
        assertEquals(Arrays.asList(41001L),db.assignedRoles);assertEquals(Arrays.asList(PROJECT),db.assignedProjects);
        assertEquals(0,db.projectMembershipChecks);
        for(TransactionSynchronization sync:TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
    }
    @Test public void ordinaryBusinessAdminCanGrantManagementDualRoleWithoutExplicitProjects() {
        wechat.review(APPLICATION,grant(Arrays.asList(41000L,41003L),Collections.emptyList()));
        assertEquals("approved",db.registration.get("status"));assertEquals(Arrays.asList(41000L,41003L),db.assignedRoles);
        assertTrue(db.assignedProjects.isEmpty());
    }
    @Test public void ordinaryBusinessAdminCanAssociateExistingUnboundAccount() {
        Map<String,Object> body=grant(Arrays.asList(41001L),Arrays.asList(PROJECT));body.put("userId",EXISTING);
        wechat.review(APPLICATION,body);
        assertEquals("approved",db.registration.get("status"));assertEquals(EXISTING,db.registration.get("user_id"));
        assertEquals(0,db.accountInsertCount);assertEquals(1,db.accountUpdateCount);
    }
    @Test public void alreadyBoundReviewerCannotRebindTheirOwnAccountToSomeoneElse() {
        Map<String,Object> body=grant(Arrays.asList(41000L),Collections.emptyList());body.put("userId",ACTOR);
        rejected(400,"已经绑定其他微信",()->wechat.review(APPLICATION,body));
    }
    @Test public void rootTargetRestrictionDoesNotReferToReviewer() {
        Map<String,Object> body=grant(Arrays.asList(41000L),Collections.emptyList());body.put("userId",1L);
        rejected(400,"超级管理员微信绑定",()->wechat.review(APPLICATION,body));
    }
    @Test public void removingBusinessAdminRoleImmediatelyPreventsReview() {
        db.actorRoles=Arrays.asList("sy_owner");
        rejected(403,"没有该操作权限",()->wechat.review(APPLICATION,grant(Arrays.asList(41001L),Arrays.asList(PROJECT))));
    }
    @Test public void inactiveReviewerIsRejectedEvenWithExistingLogin() {
        db.actorActive=false;
        rejected(401,"账号已停用",()->wechat.review(APPLICATION,grant(Arrays.asList(41001L),Arrays.asList(PROJECT))));
    }
    @Test public void scopedNewRoleStillRequiresProjectWithBusinessAdminReviewer() {
        rejected(400,"至少分配一个工程",()->wechat.review(APPLICATION,grant(Arrays.asList(41001L),Collections.emptyList())));
    }

    private static class FixtureDb extends JdbcTemplate {
        final Map<String,Object> registration=map("id",APPLICATION,"status","pending","version",0,"real_name","本地申请人");
        List<String> actorRoles=Arrays.asList("sy_admin","sy_owner");
        boolean actorActive=true;int accountInsertCount,accountUpdateCount,projectMembershipChecks;
        final List<Long> assignedRoles=new ArrayList<>(),assignedProjects=new ArrayList<>();
        String role(long id) {return id==41000L?"sy_admin":id==41001L?"sy_leader":id==41003L?"sy_owner":"";}
        @SuppressWarnings("unchecked")
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
            Object result;
            if(sql.startsWith("SELECT COUNT(*) FROM sys_user WHERE user_id=")) result=actorActive?1:0;
            else if(sql.startsWith("SELECT COUNT(*) FROM sy_project WHERE")) result=number(args[0])==PROJECT?1:0;
            else if(sql.startsWith("SELECT COUNT(*) FROM sy_project_member")) {projectMembershipChecks++;result=0;}
            else if(sql.startsWith("SELECT COUNT(*) FROM sys_role WHERE")) result=Arrays.asList("sy_admin","sy_manager","sy_owner").contains(role(number(args[0])))?1:0;
            else if(sql.startsWith("SELECT COUNT(*) FROM sys_user WHERE user_name=")) result=0;
            else if(sql.startsWith("SELECT user_id FROM sys_user WHERE user_name=")) result=CREATED;
            else throw new AssertionError("Unexpected SQL: "+sql);
            return (T)result;
        }
        @SuppressWarnings("unchecked")
        @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
            if(sql.startsWith("SELECT r.role_key FROM sys_role")) {assertEquals(ACTOR,number(args[0]));return (List<T>)actorRoles;}
            if(sql.startsWith("SELECT DISTINCT m.perms")) return Collections.emptyList();
            if(sql.startsWith("SELECT role_key FROM sys_role WHERE")) return (List<T>)Collections.singletonList(role(number(args[0])));
            throw new AssertionError("Unexpected SQL: "+sql);
        }
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
            if(sql.startsWith("SELECT * FROM sy_wechat_registration WHERE id=")) return Collections.singletonList(registration);
            if(sql.startsWith("SELECT user_name,nick_name FROM sys_user")) return Collections.singletonList(map("user_name","fixture_existing","nick_name","已有账号"));
            if(sql.startsWith("SELECT id FROM sy_wechat_registration WHERE user_id=")) return number(args[0])==ACTOR?Collections.singletonList(map("id",44L)):Collections.emptyList();
            throw new AssertionError("Unexpected SQL: "+sql);
        }
        @Override public int update(PreparedStatementCreator statement,KeyHolder holder) {
            accountInsertCount++;holder.getKeyList().add(map("user_id",CREATED));return 1;
        }
        @Override public int update(String sql,Object... args) {
            if(sql.startsWith("UPDATE sy_wechat_registration SET status='approved'")) registration.putAll(map("status","approved","user_id",args[0],"reviewed_by",args[1]));
            else if(sql.startsWith("UPDATE sys_user SET user_name=")) accountUpdateCount++;
            else if(sql.startsWith("DELETE FROM sys_user_role")) assignedRoles.clear();
            else if(sql.startsWith("INSERT INTO sys_user_role")) assignedRoles.add(number(args[1]));
            else if(sql.startsWith("DELETE FROM sy_project_member")) assignedProjects.clear();
            else if(sql.startsWith("INSERT INTO sy_project_member")) assignedProjects.add(number(args[0]));
            else throw new AssertionError("Unexpected SQL: "+sql);
            return 1;
        }
    }
}
