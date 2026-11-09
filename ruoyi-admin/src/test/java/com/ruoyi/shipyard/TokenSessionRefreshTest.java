package com.ruoyi.shipyard;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.SerializationUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.web.service.TokenService;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** A request keeps its deserialized LoginUser while another request revokes the cached token. */
public class TokenSessionRefreshTest {
    private static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis());
    protected RedisCache redis;
    protected Runnable beforeValueOperation;
    private TokenService tokens;
    private final List<String> ownedKeys=new ArrayList<>();
    private long userId,otherUserId,roleId,otherRoleId;

    @SuppressWarnings("unchecked") protected RedisCache createCache() {
        Map<String,Object> values=new HashMap<>();
        RedisTemplate<String,Object> template=mock(RedisTemplate.class);
        ValueOperations<String,Object> ops=mock(ValueOperations.class);
        when(template.opsForValue()).thenAnswer(a->{runBeforeValueOperation();return ops;});
        when(template.keys(anyString())).thenAnswer(a->new HashSet<>(values.keySet()));
        when(template.hasKey(anyString())).thenAnswer(a->values.containsKey(a.getArgument(0)));
        when(template.delete(anyString())).thenAnswer(a->values.remove(a.getArgument(0))!=null);
        when(ops.get(anyString())).thenAnswer(a->copy(values.get(a.getArgument(0))));
        doAnswer(a->{values.put(a.getArgument(0),copy(a.getArgument(1)));return null;})
            .when(ops).set(anyString(),any(),anyLong(),any(TimeUnit.class));
        when(ops.setIfPresent(anyString(),any(),anyLong(),any(TimeUnit.class))).thenAnswer(a->{
            String key=a.getArgument(0);if(!values.containsKey(key))return false;
            values.put(key,copy(a.getArgument(1)));return true;
        });
        RedisCache cache=new RedisCache();cache.redisTemplate=template;return cache;
    }
    private static Object copy(Object value){return value==null?null:SerializationUtils.deserialize(SerializationUtils.serialize(value));}
    @Before public void setup() {
        userId=IDS.incrementAndGet();otherUserId=IDS.incrementAndGet();roleId=IDS.incrementAndGet();otherRoleId=IDS.incrementAndGet();
        redis=createCache();tokens=new TokenService();
        ReflectionTestUtils.setField(tokens,"redisCache",redis);ReflectionTestUtils.setField(tokens,"header","Authorization");
        ReflectionTestUtils.setField(tokens,"secret","local-test-only-key-for-atomic-session-renewal");ReflectionTestUtils.setField(tokens,"expireTime",30);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
    @After public void cleanup() {
        try {if(redis!=null)for(String key:ownedKeys)redis.deleteObject(key);}
        finally {RequestContextHolder.resetRequestAttributes();closeCache();}
    }
    protected void closeCache() {}
    protected void runBeforeValueOperation() {
        Runnable action=beforeValueOperation;beforeValueOperation=null;
        if(action!=null)action.run();
    }
    private String login(long uid,long rid) {
        SysRole role=new SysRole();role.setRoleId(rid);role.setRoleKey("fixture_role");
        SysUser user=new SysUser();user.setUserId(uid);user.setDeptId(101L);user.setUserName("fixture_"+uid);user.setRoles(Arrays.asList(role));
        LoginUser session=new LoginUser(uid,101L,user,Collections.singleton("shipyard:work:submit"));
        String jwt=tokens.createToken(session);ownedKeys.add(CacheConstants.LOGIN_TOKEN_KEY+session.getToken());return jwt;
    }
    private LoginUser read(String jwt){MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+jwt);return tokens.getLoginUser(request);}
    private LoginUser expiringSnapshot(String jwt){LoginUser snapshot=read(jwt);assertNotNull(snapshot);snapshot.setExpireTime(System.currentTimeMillis()+1);return snapshot;}

    @Test public void newLoginAndExistingSessionRenewalRemainUsable() {
        String jwt=login(userId,roleId);LoginUser snapshot=expiringSnapshot(jwt);
        tokens.verifyToken(snapshot);LoginUser renewed=read(jwt);
        assertNotNull(renewed);assertTrue(renewed.getExpireTime()>System.currentTimeMillis()+TimeUnit.MINUTES.toMillis(29));
        renewed.setPermissions(Collections.singleton("shipyard:expense:submit"));tokens.setLoginUser(renewed);
        assertEquals(Collections.singleton("shipyard:expense:submit"),read(jwt).getPermissions());
    }
    @Test public void revokedAccountCannotBeResurrectedByLateSetLoginUser() {
        String jwt=login(userId,roleId),other=login(otherUserId,otherRoleId);LoginUser stale=read(jwt);
        tokens.revokeUserSessions(userId);assertNull(read(jwt));tokens.setLoginUser(stale);
        assertNull("Late profile/permission update must not recreate a revoked session",read(jwt));assertNotNull(read(other));
    }
    @Test public void revokedAccountCannotBeResurrectedByLateVerifyToken() {
        String jwt=login(userId,roleId);LoginUser stale=expiringSnapshot(jwt);
        tokens.revokeUserSessions(userId);assertNull(read(jwt));tokens.verifyToken(stale);
        assertNull("A request that read the session before revocation must not restore it",read(jwt));
    }
    @Test public void roleRevocationPreventsLateRefreshOnEveryAffectedDevice() {
        String first=login(userId,roleId),second=login(otherUserId,roleId),unrelated=login(IDS.incrementAndGet(),otherRoleId);
        LoginUser oldFirst=read(first),oldSecond=expiringSnapshot(second);
        tokens.revokeRoleSessions(roleId);tokens.setLoginUser(oldFirst);tokens.verifyToken(oldSecond);
        assertNull(read(first));assertNull(read(second));assertNotNull(read(unrelated));
    }
    @Test public void loggedOutOrExpiredSessionCannotBeRecreated() {
        String jwt=login(userId,roleId);LoginUser stale=read(jwt);
        tokens.delLoginUser(stale.getToken());tokens.refreshToken(stale);assertNull(read(jwt));
    }
    @Test public void freshLoginAfterRevocationDoesNotAllowOldTokenToReturn() {
        String oldJwt=login(userId,roleId);LoginUser stale=read(oldJwt);tokens.revokeUserSessions(userId);
        String freshJwt=login(userId,roleId);assertNotEquals(stale.getToken(),read(freshJwt).getToken());
        tokens.setLoginUser(stale);assertNull(read(oldJwt));assertNotNull(read(freshJwt));
    }
    @Test public void selfPasswordChangeCanPreserveOnlyItsCurrentLiveSession() {
        String current=login(userId,roleId),otherDevice=login(userId,roleId),unrelated=login(otherUserId,otherRoleId);
        LoginUser currentSession=read(current);tokens.revokeOtherUserSessions(userId,currentSession.getToken());
        tokens.setLoginUser(currentSession);
        assertNotNull(read(current));assertNull(read(otherDevice));assertNotNull(read(unrelated));
    }
    @Test public void preservingAStaleCurrentTokenCannotUndoConcurrentAdminRevocation() {
        String current=login(userId,roleId);LoginUser stale=read(current);
        tokens.revokeUserSessions(userId);tokens.revokeOtherUserSessions(userId,stale.getToken());tokens.setLoginUser(stale);
        assertNull(read(current));
    }
    @Test public void deletionImmediatelyBeforeTheWriteWinsOverAnyEarlierExistenceCheck() {
        String jwt=login(userId,roleId);LoginUser stale=read(jwt);
        // An EXISTS-then-SET implementation would observe true, lose the race here, then recreate the token.
        beforeValueOperation=()->redis.deleteObject(CacheConstants.LOGIN_TOKEN_KEY+stale.getToken());
        tokens.setLoginUser(stale);
        assertNull("The controlled deletion must run immediately before the value operation",beforeValueOperation);
        assertNull("Only atomic SET XX prevents this check/write race",read(jwt));
    }
}
