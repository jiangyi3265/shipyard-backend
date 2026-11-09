package com.ruoyi.shipyard;

import java.util.*;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ShipyardTeamCategoryTest {
    private JdbcTemplate db;
    private ShipyardAccess access;
    private ShipyardAdministration admin;
    @Before public void setup() {
        db=mock(JdbcTemplate.class,invocation->"update".equals(invocation.getMethod().getName())?1:RETURNS_DEFAULTS.answer(invocation));
        access=mock(ShipyardAccess.class);admin=new ShipyardAdministration(db,access,mock(RedisCache.class));
        when(db.queryForList(contains("FROM sy_worker WHERE id=? FOR UPDATE"),eq(3L))).thenReturn(Collections.singletonList(map("id",3L,"active",1,"team","原班组")));
        when(db.queryForList(contains("SELECT role_key FROM sys_role"),eq(String.class),eq(41001L))).thenReturn(Collections.singletonList("sy_leader"));
        when(db.queryForObject(contains("SELECT COUNT(*) FROM sys_user"),eq(Integer.class),eq("team_test"),eq(12L))).thenReturn(0);
        TransactionSynchronizationManager.initSynchronization();
    }
    @After public void cleanup(){TransactionSynchronizationManager.clearSynchronization();}
    private Map<String,Object> person(){return map("id",3L,"name","工作人员","code","T003","team","原班组","rate",35);}
    private Map<String,Object> account(){return map("id",12L,"username","team_test","name","组长","status","0","password","","roleIds",Arrays.asList(41001L),"projectIds",Arrays.asList(1L));}
    private void invalid(Runnable fn){try{fn.run();fail("Invalid category must be rejected before any write");}catch(ServiceException e){assertEquals(Integer.valueOf(400),e.getCode());assertTrue(e.getMessage().contains("班组分类"));}}
    @Test public void invalidWorkerCategoryIsRejectedBeforeWrites(){for(Object value:Arrays.asList("other",1,Arrays.asList("grinding"),map("name","painting"))){Map<String,Object>b=person();b.put("teamCategory",value);invalid(()->admin.master("person",b));}verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void workerCategoryIsSavedAndCanBeClearedWithoutChangingOldTeamName(){for(String category:Arrays.asList("grinding","painting","")){Map<String,Object>b=person();b.put("teamCategory",category);admin.master("person",b);verify(db).update("UPDATE sy_worker SET team_category=? WHERE id=?",category,3L);}verify(db,times(3)).update(startsWith("UPDATE sy_worker SET name="),eq("工作人员"),eq("T003"),eq("原班组"),any(),eq(1),eq(3L));}
    @Test public void omittedWorkerCategoryDoesNotEraseExistingAssignment(){admin.master("person",person());verify(db,never()).update(startsWith("UPDATE sy_worker SET team_category"),any(Object[].class));}
    @Test public void invalidAccountCategoryIsRejectedBeforeWrites(){for(Object value:Arrays.asList("oil",true,map("name","painting"))){Map<String,Object>b=account();b.put("teamCategory",value);invalid(()->admin.account(b));}verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void leaderCategoryIsStoredIndependentlyOfRoleAndProject(){for(String category:Arrays.asList("grinding","painting","")){Map<String,Object>b=account();b.put("teamCategory",category);admin.account(b);verify(db).update("INSERT INTO sy_user_team(user_id,team_category) VALUES(?,?) ON DUPLICATE KEY UPDATE team_category=VALUES(team_category)",12L,category);}verify(db,times(3)).update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",12L,41001L);}
    @Test public void omittedAccountCategoryDoesNotEraseAssignment(){admin.account(account());verify(db,never()).update(startsWith("INSERT INTO sy_user_team"),any(Object[].class));}
    @Test public void callerStillNeedsExistingEditPermissions(){doThrow(new ServiceException("没有该操作权限",403)).when(access).require("master:edit");try{Map<String,Object>b=person();b.put("teamCategory","grinding");admin.master("person",b);fail();}catch(ServiceException e){assertEquals(Integer.valueOf(403),e.getCode());}verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void wechatReviewPassesExplicitCategoryToAccountAndPreservesOmittedValue(){
        for(boolean included:Arrays.asList(true,false)){
            JdbcTemplate data=mock(JdbcTemplate.class);ShipyardAdministration accounts=mock(ShipyardAdministration.class);
            when(data.queryForList("SELECT * FROM sy_wechat_registration WHERE id=? FOR UPDATE",7L)).thenReturn(Collections.singletonList(map("status","pending","version",0,"real_name","申请组长")));
            when(data.queryForList("SELECT user_name,nick_name FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",12L)).thenReturn(Collections.singletonList(map("user_name","team_test","nick_name","组长")));
            when(data.queryForObject(anyString(),eq(Integer.class),eq(41001L))).thenReturn(0);
            ShipyardWechatService service=new ShipyardWechatService(data,mock(RedisCache.class),null,access,accounts,null,null,null,null);
            Map<String,Object>b=map("accept",true,"version",0,"roleIds",Arrays.asList(41001L),"projectIds",Arrays.asList(1L),"userId",12L);if(included)b.put("teamCategory","painting");
            service.review(7L,b);verify(accounts).account(argThat(a->included?"painting".equals(a.get("teamCategory")):!a.containsKey("teamCategory")));
        }
    }
}
