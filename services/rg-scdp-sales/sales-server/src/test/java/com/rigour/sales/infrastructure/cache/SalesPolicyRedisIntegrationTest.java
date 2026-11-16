package com.rigour.sales.infrastructure.cache;

import com.rigour.shared.context.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SalesPolicyRedisIntegrationTest {
    record Policy(UUID version, int radius) {}

    @Test void redisHitVersionIsolationCapacityAndLongLivedBudgetUseTheProductionAdapter() {
        try (var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4.10-alpine")).withExposedPorts(6379)) {
            redis.start();
            var env = new MockEnvironment().withProperty("spring.data.redis.host", redis.getHost())
                    .withProperty("spring.data.redis.port", redis.getMappedPort(6379).toString())
                    .withProperty("rigour.query-cache.namespace", "isolated-policy-test")
                    .withProperty("rigour.query-cache.max-tenant-bytes", "1200");
            var config = new QueryCacheConfiguration();
            var factory = config.queryCacheRedisConnectionFactory(env);
            factory.afterPropertiesSet(); factory.start();
            var meters = new SimpleMeterRegistry();
            var beans = new StaticListableBeanFactory(Map.of("metrics", meters));
            var cache = config.queryCache(factory, beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), env);
            UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), version = UUID.randomUUID();
            var caller = new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(), 1, 1, 1,
                    Set.of(), Set.of("sales:context:read"));
            ReflectionTestUtils.invokeMethod(AuthorizationContext.class, "set", caller);
            try {
                var loads = new AtomicInteger(); var type = new TypeReference<Policy>() {};
                java.util.function.Supplier<Policy> load = () -> { loads.incrementAndGet(); return new Policy(version, 200); };
                assertEquals(cache.read(tenant.toString(), "policy", version, 86400, type, load),
                        cache.read(tenant.toString(), "policy", version, 86400, type, load));
                assertEquals(1, loads.get());
                assertEquals(1, meters.get("rigour.query.cache.requests").tag("outcome", "hit").counter().count());
                cache.read(tenant.toString(), "policy", UUID.randomUUID(), 10, type, load);
                assertEquals(2, loads.get());
                String budget = "scdp:" + tenant + ":isolated-policy-test:v1:budget";
                try (var connection = factory.getConnection()) {
                    // A later short entry must not expire accounting before the 24-hour policy entry.
                    for (String suffix : List.of(":bytes", ":sizes", ":expires")) {
                        assertTrue(connection.keyCommands().pTtl((budget + suffix).getBytes(StandardCharsets.UTF_8)) > 3600000);
                    }
                }
                for (int n = 0; n < 20; n++) cache.read(tenant.toString(), "policy", UUID.randomUUID(), 86400, type, load);
                try (var connection = factory.getConnection()) {
                    byte[] value = connection.stringCommands().get((budget + ":bytes").getBytes(StandardCharsets.UTF_8));
                    assertNotNull(value);
                    assertTrue(Long.parseLong(new String(value, StandardCharsets.UTF_8)) <= 1200);
                }
                ReflectionTestUtils.invokeMethod(AuthorizationContext.class, "clear");
                int before = loads.get();
                cache.read(tenant.toString(), "policy", version, 86400, type, load);
                cache.read(tenant.toString(), "policy", version, 86400, type, load);
                assertEquals(before + 2, loads.get(), "unscoped work must not populate or reuse authorized cache entries");
            } finally {
                ReflectionTestUtils.invokeMethod(AuthorizationContext.class, "clear");
                factory.destroy(); meters.close();
            }
        }
    }
}
