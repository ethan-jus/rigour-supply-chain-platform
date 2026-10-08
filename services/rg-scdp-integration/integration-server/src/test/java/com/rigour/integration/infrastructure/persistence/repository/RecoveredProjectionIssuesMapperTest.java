package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.integration.application.port.out.DhbSyncStore.ExternalObjectMappingWrite;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.entity.ExternalObjectMappingEntity;
import com.rigour.integration.infrastructure.persistence.mapper.ExternalObjectMappingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationDeadLetterMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationReconciliationCaseMapper;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class RecoveredProjectionIssuesMapperTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory sessions;
    private static DriverManagerDataSource ds;
    private final byte[] tenant = bin(UUID.randomUUID()), actor = bin(UUID.randomUUID());
    private final LocalDateTime old = LocalDateTime.of(2026, 9, 30, 7, 0), now = old.plusDays(8);

    @BeforeAll
    static void setup() throws Exception {
        ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__integration_dinghuobao.sql"),
                new ClassPathResource("db/migration/V2__integration_sync_runtime.sql"),
                new ClassPathResource("db/migration/V12__external_object_mapping.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(IntegrationDeadLetterMapper.class);
        config.addMapper(IntegrationReconciliationCaseMapper.class);
        config.addMapper(ExternalObjectMappingMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        sessions = factory.getObject();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upsertRestoresOnlyTheMatchingSoftDeletedMapping(boolean domainBatchApi) {
        UUID tenantId = UUID.randomUUID(), connector = UUID.randomUUID(), otherConnector = UUID.randomUUID();
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(ExternalObjectMappingMapper.class);
            var store = new MybatisPlusDhbSyncStore(null, null, null, null, null, null, null,
                    mapper, null, null, null, null, new DataSourceTransactionManager(ds), JsonMapper.builder().build());
            var rows = new ArrayList<ExternalObjectMappingEntity>();
            for (var scope : List.of(new UUID[]{tenantId, connector},
                    new UUID[]{tenantId, otherConnector}, new UUID[]{UUID.randomUUID(), connector})) {
                var row = new ExternalObjectMappingEntity();
                row.id = bin(UUID.randomUUID()); row.tenantId = bin(scope[0]); row.connectorId = bin(scope[1]);
                row.sourceSystem = "DHB"; row.sourceObjectType = "CUSTOMER"; row.sourceObjectId = "source-1";
                row.internalObjectId = 11L; row.mappingStatus = "REMOVED"; row.createdAt = old; row.updatedAt = old;
                row.deletedAt = old; row.deletedBy = actor; row.deleteReason = "旧关联已解除"; row.version = 3L;
                mapper.insert(row);
                rows.add(row);
            }

            if (domainBatchApi) {
                var domainStore = new MybatisPlusDhbIntegrationStore(null, null, null, null, null,
                        mapper, null, null, null, null, null, new DataSourceTransactionManager(ds), JsonMapper.builder().build());
                assertThat(domainStore.saveExternalObjectMappings(tenantId, IntegrationUuidCodec.decode(actor),
                        List.of(new ExternalObjectMappingCommand(connector, "DHB", "CUSTOMER", "source-1", "new-customer",
                                "CRM", "CUSTOMER", 22L, "CUS-22", "ACTIVE", null, Instant.now(), null, null, null,
                                "CRM正常同步重建关联")))).isEqualTo(1);
            } else {
                store.upsertExternalObjectMapping(tenantId, IntegrationUuidCodec.decode(actor),
                        new ExternalObjectMappingWrite(connector, "CUSTOMER", "source-1", "new-customer", "CRM", "CUSTOMER",
                                22L, "CUS-22", "ACTIVE", null, Instant.now(), null, null, "正常同步重建关联"));
            }

            var restored = mapper.selectById(rows.getFirst().id);
            assertThat(restored.deletedAt).isNull();
            assertThat(restored.deletedBy).isNull();
            assertThat(restored.deleteReason).isNull();
            assertThat(restored.internalObjectId).isEqualTo(22L);
            assertThat(restored.mappingStatus).isEqualTo("ACTIVE");
            assertThat(restored.version).isEqualTo(4L);
            assertThat(store.findActiveMapping(tenantId, connector, "CUSTOMER", "source-1").internalObjectId())
                    .isEqualTo(22L);
            for (var row : rows.subList(1, rows.size())) {
                var untouched = mapper.selectById(row.id);
                assertThat(untouched.deletedAt).isEqualTo(old);
                assertThat(untouched.mappingStatus).isEqualTo("REMOVED");
                assertThat(untouched.version).isEqualTo(3L);
            }
        }
    }

    @Test
    void onlyNewerValidMappingsCloseMatchingIssuesOnceAcrossBothKeys() {
        issue("id", false, tenant);
        issue("number", true, tenant);
        issue("both", false, tenant);
        mapping("id", "different", tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, old.plusSeconds(1), false);
        mapping("different", "number", tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, old.plusSeconds(1), false);
        mapping("both", "both", tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, old.plusSeconds(1), false);
        var rejected = List.of("equal-time", "older", "missing-time", "missing-target", "inactive",
                "deleted", "other-type", "other-system", "other-tenant", "ignored");
        rejected.forEach(key -> issue(key, false, tenant));
        mapping("equal-time", null, tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, old, false);
        mapping("older", null, tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, old.minusSeconds(1), false);
        mapping("missing-time", null, tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, null, false);
        mapping("missing-target", null, tenant, "DHB", "SALES_ORDER", "ACTIVE", null, now, false);
        mapping("inactive", null, tenant, "DHB", "SALES_ORDER", "REMOVED", 1L, now, false);
        mapping("deleted", null, tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, now, true);
        mapping("other-type", null, tenant, "DHB", "CUSTOMER", "ACTIVE", 1L, now, false);
        mapping("other-system", null, tenant, "FEISHU", "SALES_ORDER", "ACTIVE", 1L, now, false);
        mapping("other-tenant", null, bin(UUID.randomUUID()), "DHB", "SALES_ORDER", "ACTIVE", 1L, now, false);
        mapping("ignored", null, tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, now, false);
        var foreign = bin(UUID.randomUUID());
        issue("foreign", false, foreign);
        mapping("foreign", null, foreign, "DHB", "SALES_ORDER", "ACTIVE", 1L, now, false);
        for (String table : List.of("integration_dead_letter", "integration_reconciliation_case"))
            jdbc.update("UPDATE " + table + " SET status='IGNORED' WHERE tenant_id=? AND "
                    + keyColumn(table) + "='ignored'", tenant);

        assertThat(resolve()).isEqualTo(6);
        assertThat(resolve()).isZero();

        for (String table : List.of("integration_dead_letter", "integration_reconciliation_case")) {
            assertThat(jdbc.queryForList("SELECT " + keyColumn(table) + " FROM " + table
                    + " WHERE tenant_id=? AND status='RESOLVED'", String.class, tenant))
                    .containsExactlyInAnyOrder("id", "number", "both");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                    + " WHERE tenant_id=? AND status='RESOLVED' AND version=1 AND resolved_at=?"
                    + " AND updated_at=? AND resolved_by=? AND updated_by=?", Long.class,
                    tenant, now, now, actor, actor)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                    + " WHERE tenant_id=? AND version=0", Long.class, tenant)).isEqualTo(rejected.size());
            assertThat(jdbc.queryForObject("SELECT status FROM " + table + " WHERE tenant_id=?",
                    String.class, foreign)).isEqualTo("OPEN");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryPlanUsesIssueSourceIndexesForBothKeys(boolean sourceNumber) {
        for (int i = 0; i < 100; i++) {
            issue("unresolved-" + i, false, tenant);
            mapping("unrelated-" + i, "unrelated-no-" + i, tenant, "DHB", "SALES_ORDER", "ACTIVE", 1L, now, false);
        }
        for (Class<?> mapper : List.of(IntegrationDeadLetterMapper.class, IntegrationReconciliationCaseMapper.class)) {
            Map<String, Object> params = Map.of("tenant", tenant, "actor", actor, "now", now,
                    "sourceSystem", "DHB", "sourceNumber", sourceNumber);
            var sql = sessions.getConfiguration().getMappedStatement(mapper.getName() + ".resolveRecovered")
                    .getBoundSql(params);
            var args = sql.getParameterMappings().stream().map(p -> params.get(p.getProperty())).toArray();
            var plan = jdbc.queryForList("EXPLAIN " + sql.getSql(), args);
            assertThat(plan.stream().filter(row -> "d".equals(row.get("table"))).toList())
                    .singleElement().satisfies(row -> {
                        assertThat(row.get("type")).isEqualTo("ref");
                        assertThat(row.get("key")).isIn("idx_integration_dead_letter_source",
                                "idx_integration_reconciliation_business");
                    });
        }
    }

    private int resolve() {
        try (var session = sessions.openSession(true)) {
            int changed = 0;
            for (boolean number : List.of(false, true)) {
                changed += session.getMapper(IntegrationDeadLetterMapper.class)
                        .resolveRecovered(tenant, actor, now, "DHB", number);
                changed += session.getMapper(IntegrationReconciliationCaseMapper.class)
                        .resolveRecovered(tenant, actor, now, "DHB", number);
            }
            return changed;
        }
    }

    private void issue(String key, boolean inProgress, byte[] issueTenant) {
        jdbc.update("INSERT INTO integration_dead_letter(id,tenant_id,source_system,source_object_type,"
                + "source_id,status,created_at,updated_at) VALUES(?,?,'DHB','SALES_ORDER',?,?,?,?)",
                bin(UUID.randomUUID()), issueTenant, key, inProgress ? "REPLAYING" : "OPEN", old, old);
        jdbc.update("INSERT INTO integration_reconciliation_case(id,tenant_id,source_system,source_object_type,"
                + "business_key,status,check_type,message,created_at,updated_at) VALUES(?,?,'DHB','SALES_ORDER',?,?,'MAPPING','test',?,?)",
                bin(UUID.randomUUID()), issueTenant, key, inProgress ? "ACKNOWLEDGED" : "OPEN", old, old);
    }

    private void mapping(String id, String number, byte[] mappingTenant, String system, String type,
            String status, Long internalId, LocalDateTime seen, boolean deleted) {
        jdbc.update("INSERT INTO integration_external_object_mapping(id,tenant_id,connector_id,source_system,source_object_type,"
                + "source_object_id,source_object_no,mapping_status,internal_object_id,last_seen_at,deleted_at,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", bin(UUID.randomUUID()), mappingTenant, bin(UUID.randomUUID()),
                system, type, id, number, status, internalId, seen, deleted ? old : null, old, old);
    }

    private static String keyColumn(String table) {
        return table.equals("integration_dead_letter") ? "source_id" : "business_key";
    }

    private static byte[] bin(UUID id) { return IntegrationUuidCodec.encode(id); }
}
