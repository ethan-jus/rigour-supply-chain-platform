package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.ExternalObjectMappingEntity;
import com.rigour.integration.infrastructure.persistence.mapper.ExternalObjectMappingMapper;
import com.rigour.shared.core.exception.BusinessException;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

/** 真实MyBatis查询核对历史来源码，防止已同步仓库被误判缺失或跨企业串用。 */
class DhbMappingSourceCompatibilityTest {
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID();
    SqlSession session;
    ExternalObjectMappingMapper mapper;
    MybatisPlusDhbSyncStore store;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new JdbcTemplate(ds)
                .execute(
                        """
CREATE TABLE integration_external_object_mapping (
id BINARY(16) PRIMARY KEY, tenant_id BINARY(16), connector_id BINARY(16),
source_system VARCHAR(32), source_object_type VARCHAR(64), source_object_id VARCHAR(128),
source_object_no VARCHAR(128), internal_domain VARCHAR(32), internal_object_type VARCHAR(64),
internal_object_id BIGINT, internal_object_no VARCHAR(128), mapping_status VARCHAR(24),
last_seen_run_id BINARY(16), last_seen_at TIMESTAMP, source_deleted_at TIMESTAMP,
payload_checksum VARCHAR(64), conflict_reason VARCHAR(1000), remark VARCHAR(1000), version BIGINT,
created_at TIMESTAMP, created_by BINARY(16), updated_at TIMESTAMP, updated_by BINARY(16),
deleted_at TIMESTAMP, deleted_by BINARY(16), delete_reason VARCHAR(512))
""");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ExternalObjectMappingMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        session = factory.getObject().openSession(true);
        mapper = session.getMapper(ExternalObjectMappingMapper.class);
        store =
                new MybatisPlusDhbSyncStore(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        mapper,
                        null,
                        null,
                        null,
                        null,
                        new DataSourceTransactionManager(ds),
                        JsonMapper.builder().build());
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
    }

    @Test
    void readsExistingDomainWarehouseBySourceIdAndNumberWithoutCreatingAnotherMapping() {
        var legacy = row(tenant, connector, "DINGHUOBAO", "26860", "12", 3L, "ACTIVE");
        legacy.internalObjectNo = "WH-RENAMED";
        mapper.updateById(legacy);
        assertThat(
                        store.findActiveMapping(tenant, connector, "WAREHOUSE", "26860")
                                .internalObjectId())
                .isEqualTo(3L);
        assertThat(store.findActiveMapping(tenant, connector, "WAREHOUSE", "12").internalObjectId())
                .isEqualTo(3L);
        assertThat(mapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void acceptsCanonicalCodeAndSameTargetAliasesButRejectsConflictingTargets() {
        row(tenant, connector, "DHB", "26860", "12", 3L, "ACTIVE");
        row(tenant, connector, "DINGHUOBAO", "26860", "12", 3L, "ACTIVE");
        assertThat(
                        store.findActiveMapping(tenant, connector, "WAREHOUSE", "26860")
                                .internalObjectId())
                .isEqualTo(3L);
        row(tenant, connector, "DINGHUOBAO", "DIFFERENT-ID", "12", 9L, "ACTIVE");
        assertThatThrownBy(() -> store.findActiveMapping(tenant, connector, "WAREHOUSE", "12"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在不同");
    }

    @Test
    void isolatesTenantConnectorPlatformAndMappingState() {
        row(UUID.randomUUID(), connector, "DINGHUOBAO", "26860", "12", 10L, "ACTIVE");
        row(tenant, UUID.randomUUID(), "DINGHUOBAO", "26860", "12", 11L, "ACTIVE");
        row(tenant, connector, "HUOLALA", "26860", "12", 12L, "ACTIVE");
        row(tenant, connector, "DINGHUOBAO", "26860", "12", 13L, "REMOVED");
        var deleted = row(tenant, connector, "DHB", "26860", "12", 14L, "ACTIVE");
        deleted.deletedAt = LocalDateTime.now();
        mapper.updateById(deleted);
        assertThat(store.findActiveMapping(tenant, connector, "WAREHOUSE", "26860")).isNull();
    }

    @Test
    void keepsExistingUnscopedLegacyFallbackButNeverUsesAnotherConnector() {
        row(tenant, null, "DINGHUOBAO", "26860", "12", 3L, "ACTIVE");
        row(tenant, UUID.randomUUID(), "DINGHUOBAO", "26860", "12", 9L, "ACTIVE");
        assertThat(
                        store.findActiveMapping(tenant, connector, "WAREHOUSE", "26860")
                                .internalObjectId())
                .isEqualTo(3L);
    }

    @Test
    void automaticDependencyRepairHonorsHumanMappingStateAndSourceScope() {
        row(tenant, UUID.randomUUID(), "DHB", "W1", "01", 9L, "IGNORED");
        assertThat(store.dependencyAutoSyncBlocked(tenant,connector,java.util.List.of("WAREHOUSE"),java.util.List.of("W1"))).isFalse();
        row(tenant, connector, "DINGHUOBAO", "W1", "01", 3L, "CONFLICT");
        assertThat(store.dependencyAutoSyncBlocked(tenant,connector,java.util.List.of("WAREHOUSE"),java.util.List.of("01"))).isTrue();
        assertThat(store.dependencyAutoSyncBlocked(UUID.randomUUID(),connector,java.util.List.of("WAREHOUSE"),java.util.List.of("W1"))).isFalse();
    }

    private ExternalObjectMappingEntity row(
            UUID owner,
            UUID connection,
            String platform,
            String sourceId,
            String sourceNo,
            Long target,
            String status) {
        var row = new ExternalObjectMappingEntity();
        row.id = IntegrationUuidCodec.encode(UUID.randomUUID());
        row.tenantId = IntegrationUuidCodec.encode(owner);
        row.connectorId = connection == null ? null : IntegrationUuidCodec.encode(connection);
        row.sourceSystem = platform;
        row.sourceObjectType = "WAREHOUSE";
        row.sourceObjectId = sourceId;
        row.sourceObjectNo = sourceNo;
        row.internalDomain = "ERP";
        row.internalObjectType = "WAREHOUSE";
        row.internalObjectId = target;
        row.internalObjectNo = "WH" + target;
        row.mappingStatus = status;
        row.version = 0L;
        row.createdAt = LocalDateTime.now();
        row.updatedAt = row.createdAt;
        mapper.insert(row);
        return row;
    }
}
