package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.analytics.application.port.out.BiSourceSnapshotClient;
import com.rigour.analytics.infrastructure.persistence.mapper.BiSourceSnapshotMapper;
import com.rigour.analytics.infrastructure.persistence.scope.BiSourceSnapshotProjector;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.*;

/** 实际 Mapper 和事务验证；JdbcTemplate 仅用于隔离测试建表和独立断言。 */
public class BiSourceSnapshotPersistenceTest {
    private final UUID tenant = UUID.randomUUID();
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private BiSourceSnapshotClient client;
    private BiSourceSnapshotProjector projector;

    public static LocalDateTime utcNow(int precision) {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:snapshot_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE ALIAS UTC_TIMESTAMP FOR"
                    + " 'com.rigour.analytics.infrastructure.persistence.repository.BiSourceSnapshotPersistenceTest.utcNow'");
        jdbc.execute(
                "CREATE TABLE bi_source_snapshot_checkpoint(tenant_id VARCHAR,dataset"
                    + " VARCHAR,source_version VARCHAR,projected_at TIMESTAMP,PRIMARY"
                    + " KEY(tenant_id,dataset))");
        jdbc.execute(
                "CREATE TABLE bi_payment_status(id BIGINT,tenant_id VARCHAR,payment_status_code"
                    + " VARCHAR,PRIMARY KEY(tenant_id,id))");
        jdbc.update(
                "INSERT INTO bi_payment_status VALUES(1,?,'CANCELLED'),(1,'OTHER','CHECKED')",
                tenant.toString());
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(BiSourceSnapshotMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var mapper =
                new SqlSessionTemplate(factory.getObject()).getMapper(BiSourceSnapshotMapper.class);
        client = mock(BiSourceSnapshotClient.class);
        when(client.version(eq(tenant), anyString(), anyString()))
                .thenAnswer(
                        call ->
                                "ORDER_PAYMENT_STATUS".equals(call.getArgument(2))
                                        ? "v1"
                                        : "UNINITIALIZED");
        var unknown = new HashMap<String, String>();
        unknown.put("id", "3");
        unknown.put("tenant_id", tenant.toString());
        unknown.put("payment_status_code", null);
        when(client.page(tenant, "ORDER", "ORDER_PAYMENT_STATUS", ""))
                .thenReturn(
                        new BiSourceSnapshotClient.Page(
                                "v1",
                                List.of(
                                        Map.of(
                                                "id",
                                                "2",
                                                "tenant_id",
                                                tenant.toString(),
                                                "payment_status_code",
                                                "RECEIVED"),
                                        unknown)));
        var proxy = new ProxyFactory(new BiSourceSnapshotProjector(mapper, client));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        projector = (BiSourceSnapshotProjector) proxy.getProxy();
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void publishesThroughMapperAndUnchangedVersionDoesNotReplaceAgain() {
        projector.refresh(tenant);
        assertThat(
                        jdbc.queryForList(
                                "SELECT payment_status_code FROM bi_payment_status WHERE"
                                    + " tenant_id=? ORDER BY id",
                                String.class,
                                tenant.toString()))
                .containsExactly("RECEIVED", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payment_status_code FROM bi_payment_status WHERE"
                                    + " tenant_id='OTHER'",
                                String.class))
                .isEqualTo("CHECKED");
        projector.refresh(tenant);
        verify(client, times(1)).page(tenant, "ORDER", "ORDER_PAYMENT_STATUS", "");
    }

    @Test
    void versionChangeRollsBackDeletionInsertionAndCheckpointTogether() {
        when(client.version(tenant, "ORDER", "ORDER_PAYMENT_STATUS")).thenReturn("v1", "v2");
        assertThatThrownBy(() -> projector.refresh(tenant))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForList(
                                "SELECT payment_status_code FROM bi_payment_status WHERE"
                                    + " tenant_id=? ORDER BY id",
                                String.class,
                                tenant.toString()))
                .containsExactly("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bi_source_snapshot_checkpoint",
                                Integer.class))
                .isZero();
    }

    @Test
    void rejectsCrossTenantRowsWithoutPublishingPartialSnapshot() {
        when(client.page(tenant, "ORDER", "ORDER_PAYMENT_STATUS", ""))
                .thenReturn(
                        new BiSourceSnapshotClient.Page(
                                "v1",
                                List.of(
                                        Map.of(
                                                "id",
                                                "2",
                                                "tenant_id",
                                                "OTHER",
                                                "payment_status_code",
                                                "RECEIVED"))));
        assertThatThrownBy(() -> projector.refresh(tenant))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_payment_status", Integer.class))
                .isEqualTo(2);
    }
}
