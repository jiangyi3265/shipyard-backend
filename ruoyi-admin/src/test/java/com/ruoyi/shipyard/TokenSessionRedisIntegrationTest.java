package com.ruoyi.shipyard;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.config.RedisConfig;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** The same renewal/revocation scenarios run against real Redis, isolated from API DB1 and WeChat DB14. */
public class TokenSessionRedisIntegrationTest extends TokenSessionRefreshTest {
    private LettuceConnectionFactory connection;
    @Override protected RedisCache createCache() {
        RedisStandaloneConfiguration config=new RedisStandaloneConfiguration("127.0.0.1",6383);
        config.setDatabase(15);
        connection=new LettuceConnectionFactory(config);connection.afterPropertiesSet();
        assertEquals("127.0.0.1",connection.getHostName());assertEquals(6383,connection.getPort());assertEquals(15,connection.getDatabase());
        RedisTemplate<Object,Object> template=new RedisConfig().redisTemplate(connection);
        RedisTemplate<Object,Object> intercepted=spy(template);
        doAnswer(a->{runBeforeValueOperation();return template.opsForValue();}).when(intercepted).opsForValue();
        RedisCache cache=new RedisCache();cache.redisTemplate=intercepted;
        RedisConnection probe=connection.getConnection();
        try {assertEquals("PONG",probe.ping());}finally{probe.close();}
        return cache;
    }
    @Override protected void closeCache(){if(connection!=null)connection.destroy();}
    @Test public void realConditionalWriteRenewsTtlButNeverCreatesAnAbsentEntry() {
        String key="shipyard:refresh-fixture:"+UUID.randomUUID();
        try {
            redis.setCacheObject(key,"before",1,TimeUnit.SECONDS);
            assertTrue(redis.setCacheObjectIfPresent(key,"after",30,TimeUnit.SECONDS));
            assertEquals("after",redis.getCacheObject(key));assertTrue(redis.getExpire(key)>=29);
            redis.deleteObject(key);
            assertFalse(redis.setCacheObjectIfPresent(key,"stale",30,TimeUnit.SECONDS));
            assertFalse(redis.hasKey(key));
        } finally {redis.deleteObject(key);}
    }
}
