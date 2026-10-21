package com.rigour.analytics.infrastructure.persistence.scope;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.application.port.out.BiAuthoritySource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** H2 不复现 InnoDB 重复键共享锁升级死锁，使用真实 MySQL 并发执行事务代理。 */
@Testcontainers
class BiAuthorityConcurrencyTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentRefreshesSerializeWithoutOverwritingCheckpoint(boolean existing) throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(ds) {
            @Override
            public int update(String sql, Object... args) {
                int result = super.update(sql, args);
                if (sql.startsWith("INSERT") && "CRM".equals(args[1])) {
                    // 放大初始化与 FOR UPDATE 之间的窗口；旧 INSERT IGNORE 会持有多个共享锁。
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return result;
            }
        };
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bi_authority_checkpoint(
                    tenant_id VARCHAR(64) NOT NULL, source_code VARCHAR(32) NOT NULL,
                    source_version VARCHAR(200) NOT NULL, projected_at DATETIME(6) NOT NULL,
                    PRIMARY KEY(tenant_id,source_code)) ENGINE=InnoDB
                """);
        UUID tenant = UUID.randomUUID();
        String version = existing ? "published-v1" : "UNINITIALIZED";
        if (existing) {
            for (String kind : List.of("CRM", "ORDER")) {
                jdbc.update("INSERT INTO bi_authority_checkpoint VALUES(?,?,?,'2026-01-01')",
                        tenant.toString(), kind, version);
            }
        }
        var source = mock(BiAuthoritySource.class);
        when(source.version(eq(tenant), anyString())).thenReturn(version);
        var proxy = new ProxyFactory(new BiAuthorityProjector(jdbc, source));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        var projector = (BiAuthorityProjector) proxy.getProxy();
        var start = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    projector.refresh(tenant);
                    return null;
                }));
            }
            for (var result : results) result.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForList(
                "SELECT source_version FROM bi_authority_checkpoint WHERE tenant_id=?",
                String.class, tenant.toString())).containsExactly(version, version);
        if (existing) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bi_authority_checkpoint WHERE tenant_id=? AND projected_at='2026-01-01'",
                    Integer.class, tenant.toString())).isEqualTo(2);
        }
        verify(source, never()).page(any(), anyString(), anyLong());
    }
}
