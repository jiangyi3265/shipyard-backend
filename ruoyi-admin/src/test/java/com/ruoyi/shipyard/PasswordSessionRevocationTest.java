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
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.web.controller.system.SysProfileController;
import com.ruoyi.web.controller.system.SysUserController;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real JWT creation/readback and controller execution; only Redis and SQL persistence are replaced. */
public class PasswordSessionRevocationTest {
    private final Map<String,Object> cached=new HashMap<>();
    private final Map<Long,String> hashes=new HashMap<>();
    private TokenService tokens;
    private ISysUserService users;
    private SysUserController accounts;
    private SysProfileController profile;
    private static final String BEFORE="Before_fixture_123!",AFTER="After_fixture_456!";

    @Before public void setup() {
        RedisCache redis=mock(RedisCache.class);
        when(redis.keys(anyString())).thenAnswer(a->new ArrayList<>(cached.keySet()));
        when(redis.getCacheObject(anyString())).thenAnswer(a->cached.get(a.getArgument(0)));
        when(redis.deleteObject(anyString())).thenAnswer(a->cached.remove(a.getArgument(0))!=null);
        doAnswer(a->{cached.put(a.getArgument(0),a.getArgument(1));return null;})
            .when(redis).setCacheObject(anyString(),any(),anyInt(),eq(TimeUnit.MINUTES));
        tokens=new TokenService();
        ReflectionTestUtils.setField(tokens,"redisCache",redis);
        ReflectionTestUtils.setField(tokens,"header","Authorization");
        ReflectionTestUtils.setField(tokens,"secret","local-test-only-signing-key-for-session-revocation");
        ReflectionTestUtils.setField(tokens,"expireTime",30);
        users=mock(ISysUserService.class);
        hashes.put(10L,SecurityUtils.encryptPassword(BEFORE));hashes.put(20L,hashes.get(10L));
        when(users.selectUserById(anyLong())).thenAnswer(a->user(a.getArgument(0)));
        when(users.resetPwd(any(SysUser.class))).thenAnswer(a->{SysUser u=a.getArgument(0);hashes.put(u.getUserId(),u.getPassword());return 1;});
        when(users.resetUserPwd(anyLong(),anyString())).thenAnswer(a->{hashes.put(a.getArgument(0),a.getArgument(1));return 1;});
        accounts=new SysUserController();profile=new SysProfileController();
        ReflectionTestUtils.setField(accounts,"userService",users);ReflectionTestUtils.setField(accounts,"tokenService",tokens);
        ReflectionTestUtils.setField(profile,"userService",users);ReflectionTestUtils.setField(profile,"tokenService",tokens);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
    @After public void cleanup() { SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes(); }
    private SysUser user(long id) {SysUser u=new SysUser();u.setUserId(id);u.setDeptId(101L);u.setUserName("fixture_"+id);u.setPassword(hashes.get(id));u.setStatus("0");u.setDelFlag("0");return u;}
    private String login(long id) {return tokens.createToken(new LoginUser(id,101L,user(id),Collections.emptySet()));}
    private LoginUser authenticated(String jwt) {MockHttpServletRequest req=new MockHttpServletRequest();req.addHeader("Authorization","Bearer "+jwt);return tokens.getLoginUser(req);}
    private void as(String jwt) {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(authenticated(jwt),null,Collections.emptyList()));}
    private Map<String,String> form(String oldPassword) {Map<String,String> p=new HashMap<>();p.put("oldPassword",oldPassword);p.put("newPassword",AFTER);return p;}

    @Test public void nativeAdminResetRevokesEveryTargetSessionButAllowsFreshLogin() {
        String first=login(10),second=login(10),other=login(20);as(other);
        SysUser reset=user(10);reset.setPassword(AFTER);
        assertEquals(200,accounts.resetPwd(reset).get(AjaxResult.CODE_TAG));
        assertNull(authenticated(first));assertNull(authenticated(second));assertNotNull(authenticated(other));
        assertTrue(SecurityUtils.matchesPassword(AFTER,hashes.get(10L)));
        assertNotNull(authenticated(login(10)));
    }
    @Test public void personalPasswordChangePreservesCurrentSessionAndRevokesOtherDevices() {
        String current=login(10),otherDevice=login(10),otherUser=login(20);as(current);
        assertEquals(200,profile.updatePwd(form(BEFORE)).get(AjaxResult.CODE_TAG));
        assertNotNull(authenticated(current));assertNull(authenticated(otherDevice));assertNotNull(authenticated(otherUser));
        assertTrue(SecurityUtils.matchesPassword(AFTER,authenticated(current).getPassword()));
        assertNotNull(authenticated(login(10)));
    }
    @Test public void failedPasswordChangesDoNotRevokeValidSessions() {
        String current=login(10),otherDevice=login(10);as(current);
        assertEquals(500,profile.updatePwd(form("wrong_fixture_password")).get(AjaxResult.CODE_TAG));
        verify(users,never()).resetUserPwd(anyLong(),anyString());
        assertNotNull(authenticated(current));assertNotNull(authenticated(otherDevice));
        when(users.resetPwd(any(SysUser.class))).thenReturn(0);
        SysUser reset=user(10);reset.setPassword(AFTER);
        assertEquals(500,accounts.resetPwd(reset).get(AjaxResult.CODE_TAG));
        assertNotNull(authenticated(current));assertNotNull(authenticated(otherDevice));
    }
}
