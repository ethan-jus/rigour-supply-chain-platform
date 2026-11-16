package com.rigour.order.infrastructure.cache;

import com.rigour.shared.cache.*;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.tenant.iam.client.SupplyAuthorizationContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.*;
import java.time.Duration;
import java.util.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rigour.query-cache.enabled", havingValue = "true")
public class QueryCacheConfiguration {
    @Bean
    LettuceConnectionFactory queryCacheRedisConnectionFactory(Environment env) {
        var server = new RedisStandaloneConfiguration(env.getProperty("spring.data.redis.host", "localhost"),
                env.getProperty("spring.data.redis.port", Integer.class, 6379));
        server.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, 0));
        String user = env.getProperty("spring.data.redis.username");
        if (user != null && !user.isBlank()) server.setUsername(user);
        String password = env.getProperty("spring.data.redis.password");
        if (password != null && !password.isBlank()) server.setPassword(password);
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(
                        env.getProperty("rigour.query-cache.command-timeout-ms", Long.class, 500L)))
                .shutdownTimeout(Duration.ofMillis(100))
                .clientOptions(io.lettuce.core.ClientOptions.builder().socketOptions(
                        io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofMillis(150)).build()).build());
        if (env.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false)) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    QueryCache queryCache(LettuceConnectionFactory queryCacheRedisConnectionFactory, JdbcTemplate jdbc,
                          ObjectProvider<MeterRegistry> metrics, Environment env) {
        var redis = new RedisTemplate<String, byte[]>();
        redis.setConnectionFactory(queryCacheRedisConnectionFactory);
        redis.setKeySerializer(new StringRedisSerializer());
        redis.setValueSerializer(RedisSerializer.byteArray());
        redis.afterPropertiesSet();
        String namespace = env.getProperty("rigour.query-cache.namespace",
                env.getProperty("spring.profiles.active", "local") + ":order") + ":v2";
        long capacity = env.getProperty("rigour.query-cache.max-tenant-bytes", Long.class, 33554432L);
        var write = new DefaultRedisScript<Long>("""
            local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
            local used=tonumber(redis.call('GET',KEYS[4]) or '0')
            local expired=redis.call('ZRANGEBYSCORE',KEYS[3],'-inf',now,'LIMIT',0,100)
            for _,key in ipairs(expired) do
              used=used-tonumber(redis.call('HGET',KEYS[2],key) or '0')
              redis.call('HDEL',KEYS[2],key); redis.call('ZREM',KEYS[3],key); redis.call('DEL',key)
            end
            used=used-tonumber(redis.call('HGET',KEYS[2],KEYS[1]) or '0')+string.len(ARGV[1])+256
            redis.call('SET',KEYS[1],ARGV[1],'PX',ARGV[2])
            redis.call('HSET',KEYS[2],KEYS[1],string.len(ARGV[1])+256)
            redis.call('ZADD',KEYS[3],now+tonumber(ARGV[2]),KEYS[1])
            while used>tonumber(ARGV[3]) do
              local keys=redis.call('ZRANGE',KEYS[3],0,0)
              if #keys==0 then break end
              local key=keys[1]; used=used-tonumber(redis.call('HGET',KEYS[2],key) or '0')
              redis.call('HDEL',KEYS[2],key); redis.call('ZREM',KEYS[3],key); redis.call('DEL',key)
            end
            redis.call('SET',KEYS[4],math.max(0,used),'PX',3600000)
            redis.call('PEXPIRE',KEYS[2],3600000); redis.call('PEXPIRE',KEYS[3],3600000)
            return 1
            """, Long.class);
        var store = new CacheStore() {
            private String key(String tenant, String key) { return "scdp:" + tenant + ":" + key; }
            public Optional<byte[]> get(String tenant, String key) { return Optional.ofNullable(redis.opsForValue().get(key(tenant,key))); }
            public void put(String tenant, String key, byte[] value, Duration ttl) {
                String budget = key(tenant,namespace)+":budget";
                redis.execute(write,List.of(key(tenant,key),budget+":sizes",budget+":expires",budget+":bytes"),
                        value,Long.toString(ttl.toMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Long.toString(capacity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            public void evict(String tenant, String key) { redis.delete(key(tenant,key)); }
            public boolean tryLock(String tenant,String key,String token,Duration lease) {
                return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(tenant,key)+":lock",token.getBytes(java.nio.charset.StandardCharsets.UTF_8),lease));
            }
            public void unlock(String tenant,String key,String token) {
                redis.execute(new DefaultRedisScript<Long>("if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class),
                        List.of(key(tenant,key)+":lock"),token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        return new QueryCache(store, new JdbcCacheVersions(jdbc), () -> {
            var caller = AuthorizationContext.current().orElse(null);
            if (caller == null || !"TENANT".equals(caller.principalScope())) return null;
            var policy = SupplyAuthorizationContext.requireAction("order:read");
            return Arrays.asList(caller, policy);
        }, () -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes request)) return false;
            String path = request.getRequest().getRequestURI();
            return path.endsWith("/export") || "true".equals(request.getRequest().getHeader("X-Query-Cache-Bypass"))
                    || "no-cache".equals(request.getRequest().getHeader("Cache-Control"));
        }, metrics.getIfAvailable(), namespace,
                env.getProperty("rigour.query-cache.max-entry-bytes", Integer.class, 2097152),
                env.getProperty("rigour.query-cache.max-concurrent-loads", Integer.class, 16));
    }
}
