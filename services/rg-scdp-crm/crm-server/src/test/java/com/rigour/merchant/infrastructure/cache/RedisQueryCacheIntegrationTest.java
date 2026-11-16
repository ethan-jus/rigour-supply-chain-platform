package com.rigour.merchant.infrastructure.cache;

import com.rigour.shared.context.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.type.TypeReference;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RedisQueryCacheIntegrationTest {
    @Test void commandTimeoutCanBeTunedWithoutChangingTheConnectionTimeout() {
        var config = new QueryCacheConfiguration();
        var defaults = config.queryCacheRedisConnectionFactory(new MockEnvironment());
        assertEquals(java.time.Duration.ofMillis(500), defaults.getClientConfiguration().getCommandTimeout());
        var custom = config.queryCacheRedisConnectionFactory(new MockEnvironment()
                .withProperty("rigour.query-cache.command-timeout-ms", "750"));
        assertEquals(java.time.Duration.ofMillis(750), custom.getClientConfiguration().getCommandTimeout());
    }
    record Payload(String name, java.math.BigDecimal amount) {}
    @Test void realRedisSerializationScriptsCapacityAndDurableInvalidation() {
        try(var redis=new GenericContainer<>(DockerImageName.parse("redis:7.4.10-alpine")).withExposedPorts(6379)) {
            redis.start();
            var env=new MockEnvironment().withProperty("spring.data.redis.host",redis.getHost())
                    .withProperty("spring.data.redis.port",redis.getMappedPort(6379).toString())
                    .withProperty("rigour.query-cache.namespace","isolated-test")
                    .withProperty("rigour.query-cache.max-tenant-bytes","1200");
            var config=new QueryCacheConfiguration();var factory=config.queryCacheRedisConnectionFactory(env);
            factory.afterPropertiesSet();factory.start();
            var ds=new DriverManagerDataSource("jdbc:h2:mem:redis-cache;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            var jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE query_cache_version(tenant_id VARCHAR(64) PRIMARY KEY,revision BIGINT NOT NULL)");
            var meters=new SimpleMeterRegistry();var beans=new StaticListableBeanFactory(Map.of("metrics",meters));
            var cache=config.queryCache(factory,jdbc,beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),env);
            var tenant=UUID.randomUUID();var user=UUID.randomUUID();
            var caller=new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of("crm:pool:public-read"));
            ReflectionTestUtils.invokeMethod(AuthorizationContext.class,"set",caller);
            try {
                var loads=new AtomicInteger();var type=new TypeReference<Payload>(){};
                java.util.function.Supplier<Payload> load=()->{loads.incrementAndGet();return new Payload("门店",new java.math.BigDecimal("123.456789"));};
                var a=cache.read(tenant.toString(),"stores",1,10,type,load);
                var hit=cache.read(tenant.toString(),"stores",1,10,type,load);
                assertEquals(a,hit);assertEquals(1,loads.get());assertEquals(1,meters.get("rigour.query.cache.requests").tag("outcome","hit").counter().count());
                new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(s->cache.changed(tenant.toString()));
                cache.read(tenant.toString(),"stores",1,10,type,load);assertEquals(2,loads.get());
                var largeLoads = new AtomicInteger();
                var large = new Payload("门店".repeat(20000), new java.math.BigDecimal("123.456789"));
                java.util.function.Supplier<Payload> loadLarge = () -> { largeLoads.incrementAndGet(); return large; };
                assertEquals(large, cache.read(tenant.toString(),"large",1,10,type,loadLarge));
                assertEquals(large, cache.read(tenant.toString(),"large",1,10,type,loadLarge));
                assertEquals(1, largeLoads.get());
                for(int n=2;n<15;n++)cache.read(tenant.toString(),"stores",n,10,type,load);
                try(var connection=factory.getConnection()) {
                    byte[] value=connection.stringCommands().get(("scdp:"+tenant+":isolated-test:v2:budget:bytes").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    assertNotNull(value);assertTrue(Long.parseLong(new String(value,java.nio.charset.StandardCharsets.UTF_8))<=1200);
                }
            } finally { ReflectionTestUtils.invokeMethod(AuthorizationContext.class,"clear");factory.destroy(); }
        }
    }
}
