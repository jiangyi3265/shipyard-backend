package com.ruoyi.shipyard;

import java.util.*;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.Assert.*;

/** Runs the real permission gate against an in-memory database boundary. */
public class ShipyardAccessMembershipTest {
    private static class FixtureDb extends JdbcTemplate {
        long userId=20L;
        boolean enabled=true;
        List<String> roles=new ArrayList<>(Collections.singletonList("common"));
        List<String> grants=new ArrayList<>(Arrays.asList("shipyard:account:edit", "shipyard:master:edit"));
        int grantReads;

        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
            if(sql.startsWith("SELECT COUNT(*) FROM sys_user WHERE user_id=")) return type.cast(enabled?1:0);
            throw new AssertionError("Unexpected query: "+sql);
        }
        @SuppressWarnings("unchecked")
        @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
            if(sql.startsWith("SELECT r.role_key FROM sys_role")) return (List<T>)new ArrayList<>(roles);
            if(sql.startsWith("SELECT DISTINCT m.perms")) {grantReads++;return (List<T>)new ArrayList<>(grants);}
            throw new AssertionError("Unexpected query: "+sql);
        }
        ShipyardAccess access() {
            return new ShipyardAccess(this) { @Override public long uid(){return userId;} };
        }
    }

    private void rejected(int code,String message,Runnable action) {
        try {action.run();fail("Expected membership rejection before granting business access");}
        catch(ServiceException e) {assertEquals(Integer.valueOf(code),e.getCode());assertTrue(e.getMessage().contains(message));}
    }

    @Test public void unrelatedRoleCannotUseAccountOrMasterPrivilegesEvenIfMenusWereGranted() {
        FixtureDb db=new FixtureDb();ShipyardAccess access=db.access();
        for(String permission:Arrays.asList("account:edit","master:edit"))
            rejected(403,"未分配船厂角色",()->access.require(permission));
        assertEquals("Non-member grants must not be used",0,db.grantReads);
    }

    @Test public void rawPermissionLookupForUploadAlsoRejectsNonMembers() {
        FixtureDb db=new FixtureDb();
        rejected(403,"未分配船厂角色",()->db.access().permissions());
        assertEquals(0,db.grantReads);
    }

    @Test public void removingLastBusinessRoleImmediatelyInvalidatesExistingAccessObject() {
        FixtureDb db=new FixtureDb();db.roles=Collections.singletonList("sy_safety");
        ShipyardAccess access=db.access();access.require("master:edit");
        db.roles=Collections.singletonList("common");
        rejected(403,"未分配船厂角色",()->access.require("master:edit"));
    }

    @Test public void noRolesIsNotEquivalentToAnUnrestrictedAccount() {
        FixtureDb db=new FixtureDb();db.roles=Collections.emptyList();
        rejected(403,"未分配船厂角色",()->db.access().permissions());
    }

    @Test public void allSixBusinessRolesRetainExplicitFunctionGrants() {
        for(String role:Arrays.asList("sy_admin","sy_leader","sy_manager","sy_owner","sy_accountant","sy_safety")) {
            FixtureDb db=new FixtureDb();db.roles=Collections.singletonList(role);
            db.access().require("master:edit");
        }
    }

    // Q23: account permission management stays with administrators even if a role was granted it.
    @Test public void onlyAdministratorsKeepAccountManagement() {
        FixtureDb admin=new FixtureDb();admin.roles=Collections.singletonList("sy_admin");
        admin.access().require("account:edit");
        for(String role:Arrays.asList("sy_leader","sy_manager","sy_owner","sy_accountant","sy_safety")) {
            FixtureDb db=new FixtureDb();db.roles=Collections.singletonList(role);
            assertFalse(role,db.access().permissions().contains("shipyard:account:edit"));
            try {db.access().require("account:edit");fail(role+" must not manage accounts");}
            catch(ServiceException expected) {assertEquals(Integer.valueOf(403),expected.getCode());}
        }
    }

    @Test public void rootAccountRemainsAllowedWithoutBusinessRoleRows() {
        FixtureDb db=new FixtureDb();db.userId=1L;db.roles=Collections.emptyList();
        assertTrue(db.access().permissions().contains("*:*:*"));
    }

    @Test public void disabledRootAndDisabledBusinessAccountsAreAlwaysRejected() {
        for(long id:Arrays.asList(1L,20L)) {
            FixtureDb db=new FixtureDb();db.userId=id;db.enabled=false;db.roles=Collections.singletonList("sy_admin");
            rejected(401,"账号已停用",()->db.access().permissions());
        }
    }
}
