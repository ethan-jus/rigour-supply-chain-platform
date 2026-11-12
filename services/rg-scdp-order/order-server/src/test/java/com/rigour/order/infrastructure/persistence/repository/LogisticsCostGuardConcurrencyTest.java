package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.application.port.out.LogisticsImportStore;
import com.rigour.order.infrastructure.persistence.mapper.OrderLogisticsCostMapper;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 两种生产入口共用租户互斥，取锁本身不应改变费用版本；MySQL 锁机制另做真实库隔离键验证。 */
class LogisticsCostGuardConcurrencyTest {
    Connection keeper;
    JdbcTemplate jdbc;
    TransactionTemplate transactions;
    JdbcPlatformTransportStore platform;
    OrderLogisticsCostMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:guard_" + UUID.randomUUID() + ";MODE=MySQL;LOCK_TIMEOUT=10000", "sa", "");
        keeper = ds.getConnection();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE order_logistics_cost_guard(tenant_id VARCHAR(36) PRIMARY KEY,version BIGINT NOT NULL)");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
        platform = new JdbcPlatformTransportStore(jdbc, JsonMapper.builder().build(), mock(LogisticsImportStore.class));
        var configuration = new MybatisConfiguration();
        configuration.addMapper(OrderLogisticsCostMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(OrderLogisticsCostMapper.class);
    }

    @AfterEach
    void close() throws Exception {
        keeper.close();
    }

    @Test
    void initialCreationAndMixedWritersSerializeWithoutLosingVersions() throws Exception {
        jdbc.update("INSERT INTO order_logistics_cost_guard VALUES('OTHER',19)");
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int n = 0; n < 8; n++) {
                boolean fromPlatform = n % 2 == 0;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    for (int run = 0; run < 20; run++) transactions.executeWithoutResult(status -> {
                        if (fromPlatform) platform.lockTenant("T");
                        else {
                            mapper.ensureGuard("T");
                            mapper.lock("T");
                        }
                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try {
                            jdbc.update("UPDATE order_logistics_cost_guard SET version=version+1 WHERE tenant_id='T'");
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                }));
            }
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertThat(maximum.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM order_logistics_cost_guard WHERE tenant_id='T'", Long.class)).isEqualTo(160L);
        assertThat(jdbc.queryForObject("SELECT version FROM order_logistics_cost_guard WHERE tenant_id='OTHER'", Long.class)).isEqualTo(19L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_logistics_cost_guard", Integer.class)).isEqualTo(2);
    }

    @Test
    void takingTheLockDoesNotAdvanceVersionAndRollbackReleasesIt() {
        jdbc.update("INSERT INTO order_logistics_cost_guard VALUES('T',7)");
        transactions.executeWithoutResult(status -> {
            platform.lockTenant("T");
            mapper.ensureGuard("T");
            assertThat(mapper.lock("T")).isEqualTo(7L);
            jdbc.update("UPDATE order_logistics_cost_guard SET version=99 WHERE tenant_id='T'");
            status.setRollbackOnly();
        });
        transactions.executeWithoutResult(status -> {
            mapper.ensureGuard("T");
            assertThat(mapper.lock("T")).isEqualTo(7L);
            platform.lockTenant("T");
        });
    }
}
