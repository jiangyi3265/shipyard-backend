package com.ruoyi.shipyard;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.domain.SysUserRole;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.web.controller.system.SysRoleController;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real JWT and native role controller; Redis and persistence are in-memory boundaries. */
public class RoleSessionRevocationTest {
    private final Map<String,Object> cache=new HashMap<>();
    private TokenService tokens;
    private ISysRoleService roles;
    private SysRoleController controller;
    private String first,second,unrelated;

    @Before public void setup() {
        RedisCache redis=mock(RedisCache.class);
        when(redis.keys(anyString())).thenAnswer(a->new ArrayList<>(cache.keySet()));
        when(redis.getCacheObject(anyString())).thenAnswer(a->cache.get(a.getArgument(0)));
        when(redis.deleteObject(anyString())).thenAnswer(a->cache.remove(a.getArgument(0))!=null);
        doAnswer(a->{cache.put(a.getArgument(0),a.getArgument(1));return null;}).when(redis).setCacheObject(anyString(),any(),anyInt(),eq(TimeUnit.MINUTES));
        when(redis.setCacheObjectIfPresent(anyString(),any(),anyInt(),eq(TimeUnit.MINUTES))).thenAnswer(a->{
            String key=a.getArgument(0);if(!cache.containsKey(key))return false;
            cache.put(key,a.getArgument(1));return true;
        });
        tokens=new TokenService();
        ReflectionTestUtils.setField(tokens,"redisCache",redis);ReflectionTestUtils.setField(tokens,"header","Authorization");
        ReflectionTestUtils.setField(tokens,"secret","local-test-only-signing-key-for-role-revocation");ReflectionTestUtils.setField(tokens,"expireTime",30);
        roles=mock(ISysRoleService.class);controller=new SysRoleController();
        ReflectionTestUtils.setField(controller,"roleService",roles);ReflectionTestUtils.setField(controller,"tokenService",tokens);
        when(roles.checkRoleNameUnique(any(SysRole.class))).thenReturn(true);
        when(roles.checkRoleKeyUnique(any(SysRole.class))).thenReturn(true);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        first=login(101,10);second=login(102,10);unrelated=login(1,1);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(read(unrelated),null,Collections.emptyList()));
    }
    @After public void cleanup(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
    private SysRole role(long id){SysRole r=new SysRole();r.setRoleId(id);r.setRoleName("fixture-role-"+id);r.setRoleKey("fixture_"+id);r.setStatus("0");return r;}
    private String login(long id,long roleId){SysUser u=new SysUser();u.setUserId(id);u.setDeptId(101L);u.setUserName("fixture_"+id);u.setRoles(Arrays.asList(role(roleId)));return tokens.createToken(new LoginUser(id,101L,u,Collections.singleton("system:user:list")));}
    private LoginUser read(String token){MockHttpServletRequest req=new MockHttpServletRequest();req.addHeader("Authorization","Bearer "+token);return tokens.getLoginUser(req);}
    private void bothRevoked(){assertNull(read(first));assertNull(read(second));assertNotNull(read(unrelated));}
    private void allPreserved(){assertNotNull(read(first));assertNotNull(read(second));assertNotNull(read(unrelated));}
    private void ok(AjaxResult result){assertEquals(200,result.get(AjaxResult.CODE_TAG));}

    @Test public void rolePermissionEditRevokesAllMembersCachedPermissions(){when(roles.updateRole(any(SysRole.class))).thenReturn(1);ok(controller.edit(role(10)));bothRevoked();}
    @Test public void roleDisableRevokesAllMembersOldTokens(){when(roles.updateRoleStatus(any(SysRole.class))).thenReturn(1);SysRole changed=role(10);changed.setStatus("1");ok(controller.changeStatus(changed));bothRevoked();}
    @Test public void dataScopeChangeRevokesOldScopeSnapshots(){when(roles.authDataScope(any(SysRole.class))).thenReturn(1);ok(controller.dataScope(role(10)));bothRevoked();}
    @Test public void roleDeletionRevokesCachedRoleMembership(){when(roles.deleteRoleByIds(any(Long[].class))).thenReturn(1);ok(controller.remove(new Long[]{10L}));bothRevoked();}
    @Test public void singleRoleUnassignmentRevokesOnlyTheTargetUser(){when(roles.deleteAuthUser(any(SysUserRole.class))).thenReturn(1);SysUserRole ur=new SysUserRole();ur.setUserId(101L);ur.setRoleId(10L);ok(controller.cancelAuthUser(ur));assertNull(read(first));assertNotNull(read(second));assertNotNull(read(unrelated));}
    @Test public void batchRoleUnassignmentRevokesEveryTargetUser(){when(roles.deleteAuthUsers(anyLong(),any(Long[].class))).thenReturn(2);ok(controller.cancelAuthUserAll(10L,new Long[]{101L,102L}));bothRevoked();}
    @Test public void batchRoleAssignmentForcesTargetsToReceiveFreshPermissions(){when(roles.insertAuthUsers(anyLong(),any(Long[].class))).thenReturn(2);ok(controller.selectAuthUserAll(20L,new Long[]{101L,102L}));bothRevoked();}
    @Test public void failedRoleMutationsDoNotInvalidateSessions(){
        assertEquals(500,controller.edit(role(10)).get(AjaxResult.CODE_TAG));
        assertEquals(500,controller.changeStatus(role(10)).get(AjaxResult.CODE_TAG));
        assertEquals(500,controller.dataScope(role(10)).get(AjaxResult.CODE_TAG));
        assertEquals(500,controller.remove(new Long[]{10L}).get(AjaxResult.CODE_TAG));
        SysUserRole ur=new SysUserRole();ur.setUserId(101L);ur.setRoleId(10L);
        assertEquals(500,controller.cancelAuthUser(ur).get(AjaxResult.CODE_TAG));
        assertEquals(500,controller.cancelAuthUserAll(10L,new Long[]{101L,102L}).get(AjaxResult.CODE_TAG));
        assertEquals(500,controller.selectAuthUserAll(20L,new Long[]{101L,102L}).get(AjaxResult.CODE_TAG));
        allPreserved();
    }
}
