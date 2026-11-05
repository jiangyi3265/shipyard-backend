package com.ruoyi.shipyard;

import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ShipyardAdministrationTest {
    private JdbcTemplate db;
    private ShipyardAdministration administration;

    @Before public void setup() {
        db = mock(JdbcTemplate.class,invocation -> "update".equals(invocation.getMethod().getName())?1:RETURNS_DEFAULTS.answer(invocation));
        ShipyardAccess access = mock(ShipyardAccess.class);
        administration = new ShipyardAdministration(db,access,mock(RedisCache.class));
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM sys_role"),eq(Integer.class),anyLong())).thenReturn(1);
        when(db.queryForObject(eq("SELECT COUNT(*) FROM sys_user WHERE user_name=? AND user_id<>?"),eq(Integer.class),eq("audit_user"),eq(12L))).thenReturn(0);
        TransactionSynchronizationManager.initSynchronization();
    }
    @After public void cleanup() { TransactionSynchronizationManager.clearSynchronization(); }

    private Map<String,Object> account(String status,List<Long> roles,List<Long> projects) {
        return map("id",12L,"username","audit_user","name","验收人员","status",status,
                "password","","roleIds",roles,"projectIds",projects);
    }
    private void role(long id,String key) {
        when(db.queryForList("SELECT role_key FROM sys_role WHERE role_id=? AND role_key LIKE 'sy_%' AND status='0' AND del_flag='0'",String.class,id))
                .thenReturn(Collections.singletonList(key));
    }

    @Test public void activeLeaderCannotBeSavedWithoutAnAccessibleProject() {
        role(41001L,"sy_leader");
        try { administration.account(account("0",Arrays.asList(41001L),Collections.emptyList())); fail("Enabled leader with no projects would get an unusable workbench"); }
        catch(ServiceException expected) { assertTrue(expected.getMessage().contains("工程")); }
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test public void activeAccountantAndSafetyAlsoRequireProjects() {
        for (String key : Arrays.asList("sy_accountant","sy_safety")) {
            role(41004L,key);
            try { administration.account(account("0",Arrays.asList(41004L),Collections.emptyList())); fail("Enabled scoped role must have a project"); }
            catch(ServiceException expected) { assertTrue(expected.getMessage().contains("工程")); }
        }
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test public void eachManagementRoleAndDualRoleCanHaveAllProjects() {
        for(String key:Arrays.asList("sy_admin","sy_manager","sy_owner")) {
            role(41000L,key);
            administration.account(account("0",Arrays.asList(41000L),Collections.emptyList()));
        }
        role(41001L,"sy_leader");role(41003L,"sy_owner");
        administration.account(account("0",Arrays.asList(41001L,41003L),Collections.emptyList()));
    }

    @Test public void validAssignedLeaderAndDisabledUnassignedAccountRemainEditable() {
        role(41001L,"sy_leader");
        administration.account(account("0",Arrays.asList(41001L),Arrays.asList(1L)));
        administration.account(account("1",Arrays.asList(41001L),Collections.emptyList()));
    }

    @Test public void deletedOrDisabledRoleCannotBeAssigned() {
        when(db.queryForList("SELECT role_key FROM sys_role WHERE role_id=? AND role_key LIKE 'sy_%' AND status='0' AND del_flag='0'",String.class,41001L)).thenReturn(Collections.emptyList());
        try { administration.account(account("0",Arrays.asList(41001L),Arrays.asList(1L))); fail("Deleted or disabled roles must be rejected"); }
        catch(ServiceException expected) { assertTrue(expected.getMessage().contains("角色")); }
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
