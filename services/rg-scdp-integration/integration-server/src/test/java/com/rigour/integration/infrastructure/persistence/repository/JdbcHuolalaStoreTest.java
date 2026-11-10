package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.integration.application.port.out.HuolalaStore;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public class JdbcHuolalaStoreTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    HuolalaStore store;
    ObjectMapper json = new ObjectMapper();
    UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID();

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean
        DriverManagerDataSource dataSource() {
            return new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "sa",
                    "",
                    true);
        }

        @Bean
        JdbcTemplate jdbc(DriverManagerDataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DriverManagerDataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcHuolalaStore store(JdbcTemplate jdbc) {
            return new JdbcHuolalaStore(jdbc, new ObjectMapper());
        }

        @Bean
        static PersistenceExceptionTranslationPostProcessor exceptionTranslation() {
            return new PersistenceExceptionTranslationPostProcessor();
        }
    }

    @Test
    void packagedVersionedMigrationsHaveUniqueVersions() throws Exception {
        var resources =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/migration/V*__*.sql");
        var scripts = new LinkedHashMap<String, List<String>>();
        for (var resource : resources) {
            var filename = resource.getFilename();
            var version = filename.substring(1, filename.indexOf("__")).replace('_', '.');
            scripts.computeIfAbsent(version, key -> new ArrayList<>()).add(filename);
        }
        assertThat(scripts).isNotEmpty();
        scripts.forEach(
                (version, files) -> assertThat(files).as("Flyway version %s", version).hasSize(1));
    }

    @BeforeEach
    void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        store = context.getBean(HuolalaStore.class);
        jdbc.execute(
                "CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR '"
                        + JdbcHuolalaStoreTest.class.getName()
                        + ".utcNow'");
        create("V28__huolala_connection.sql", "integration_huolala_connector");
        create("V1__integration_dinghuobao.sql", "integration_sync_task");
        create("V1__integration_dinghuobao.sql", "integration_raw_landing");
        jdbc.execute(
                "ALTER TABLE integration_raw_landing DROP CONSTRAINT"
                        + " uk_integration_raw_landing_source");
        for (String column :
                List.of(
                        "connector_id BINARY(16)",
                        "run_id BINARY(16)",
                        "processed_at DATETIME(6)",
                        "error_message VARCHAR(2000)",
                        "attempts INT DEFAULT 0",
                        "last_attempt_at DATETIME(6)"))
            jdbc.execute("ALTER TABLE integration_raw_landing ADD " + column);
        jdbc.execute(
                "CREATE UNIQUE INDEX raw_revision ON"
                    + " integration_raw_landing(tenant_id,source_system,source_object_type,source_id,payload_checksum)");
        create("V2__integration_sync_runtime.sql", "integration_sync_run");
        jdbc.execute("ALTER TABLE integration_sync_run ADD hll_stages_json LONGTEXT");
        create("V2__integration_sync_runtime.sql", "integration_dead_letter");
        create("V2__integration_sync_runtime.sql", "integration_outbox_event");
        create(
                "V14__upgrade_external_object_mapping_schema.sql",
                "integration_external_object_mapping");
        create("V17__integration_manual_resolution.sql", "integration_manual_resolution");
        store.save(tenant, actor, settings(0));
    }

    public static LocalDateTime utcNow(int precision) {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    void create(String file, String table) throws Exception {
        String sql =
                new String(
                        getClass().getResourceAsStream("/db/migration/" + file).readAllBytes(),
                        StandardCharsets.UTF_8);
        int start = sql.indexOf("CREATE TABLE " + table + " (");
        if (start < 0) start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
        int end = sql.indexOf(") ENGINE=", start);
        sql =
                sql.substring(start, end + 1)
                        .replaceAll("BIGINT\\(20\\)", "BIGINT")
                        .replaceAll("(?i)\\bJSON\\b", "LONGTEXT");
        jdbc.execute(sql);
    }

    SaveConnection settings(long revision) {
        return new SaveConnection(
                "12345",
                "示例企业",
                "env://HLL",
                false,
                "http://localhost:5100/huolala-authorized.html",
                true,
                30,
                LocalDate.now().minusMonths(1),
                revision);
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void authorizationIsSingleUseActorBoundAndConfigurationUsesRevision() {
        store.beginAuth(tenant, actor, "hash", Instant.now().plusSeconds(600));
        assertThat(store.consumeAuth(tenant, UUID.randomUUID(), "hash", Instant.now())).isFalse();
        assertThat(store.consumeAuth(tenant, actor, "wrong", Instant.now())).isFalse();
        assertThat(store.consumeAuth(tenant, actor, "hash", Instant.now())).isTrue();
        assertThat(store.consumeAuth(tenant, actor, "hash", Instant.now())).isFalse();
        store.authorized(tenant, Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> store.save(tenant, actor, settings(1)))
                .hasMessageContaining("状态已变化");
        var c = store.save(tenant, actor, settings(2));
        assertThat(c.authStatus()).isEqualTo("AUTHORIZED");
        assertThat(store.connection(UUID.randomUUID())).isEmpty();
    }

    @Test
    void leaseExcludesConcurrentJobsAndRecoversAbandonedRun() {
        var now = Instant.now();
        var first = store.startRun(tenant, actor, "ALL", "MANUAL", now);
        assertThat(store.claim(tenant, first.toString(), now)).isTrue();
        assertThat(store.claim(tenant, UUID.randomUUID().toString(), now)).isFalse();
        assertThatThrownBy(() -> store.save(tenant, actor, settings(1)))
                .isInstanceOf(IllegalStateException.class);
        var next = now.plusSeconds(301);
        assertThat(store.claim(tenant, UUID.randomUUID().toString(), next)).isTrue();
        assertThat(store.runs(tenant).getFirst().status()).isEqualTo("FAILED");
        assertThatThrownBy(() -> store.heartbeat(tenant, first.toString(), next))
                .hasMessageContaining("租约失效");
    }

    @Test
    void employeeMappingIsAuditedTenantScopedAndLatestObservationWins() throws Exception {
        var c = UUID.fromString(store.connection(tenant).orElseThrow().id());
        var run = store.startRun(tenant, actor, "ALL", "MANUAL", Instant.now());
        var a =
                json.readTree(
                        "{\"staff_id\":11,\"user_id\":22,\"ep_id\":12345,\"staff_name\":\"甲\"}");
        var b =
                json.readTree(
                        "{\"staff_id\":11,\"user_id\":22,\"ep_id\":12345,\"staff_name\":\"乙\"}");
        store.observeEmployee(tenant, c, run, a);
        store.observeEmployee(tenant, c, run, b);
        store.observeEmployee(tenant, c, run, a);
        assertThat(store.employees(tenant).getFirst().name()).isEqualTo("甲");
        var employee = new EmployeeCandidate(91L, "EMP91", "李经理", "13800000000", "销售一部", "ACTIVE");
        store.bind(
                tenant, actor, store.employee(tenant, "11").orElseThrow(), employee, 0, "核对手机号后关联");
        assertThat(store.mappedEmployee(tenant, "22")).isEqualTo("EMP91");
        assertThat(store.employees(tenant).getFirst().departmentName()).isEqualTo("销售一部");
        assertThatThrownBy(
                        () ->
                                store.bind(
                                        tenant,
                                        actor,
                                        store.employee(tenant, "11").orElseThrow(),
                                        null,
                                        0,
                                        "解除"))
                .isInstanceOf(IllegalStateException.class);
        store.bind(tenant, actor, store.employee(tenant, "11").orElseThrow(), null, 1, "重新核对人员");
        assertThat(store.mappedEmployee(tenant, "22")).isNull();
        assertThat(store.employees(UUID.randomUUID())).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM integration_manual_resolution", Long.class))
                .isEqualTo(2);
    }

    @Test
    void rawDedupIssuesAndHistoryWindowRemainInspectable() {
        var now = Instant.now();
        var run = store.startRun(tenant, actor, "ALL", "SCHEDULED", now);
        var c = UUID.fromString(store.connection(tenant).orElseThrow().id());
        var raw =
                store.raw(
                        tenant, c, run, "ORDER", "98765", json.createObjectNode().put("bill", 10));
        assertThat(
                        store.raw(
                                tenant,
                                c,
                                run,
                                "ORDER",
                                "98765",
                                json.createObjectNode().put("bill", 10)))
                .isEqualTo(raw);
        store.delivery(tenant, run, raw, "98765", "金额待核对");
        assertThat(store.issues(tenant, run))
                .extracting(SyncIssue::message)
                .containsExactly("金额待核对");
        assertThat(store.issues(UUID.randomUUID(), run)).isEmpty();
        store.delivery(tenant, run, raw, "98765", null);
        assertThat(jdbc.queryForObject("SELECT status FROM integration_dead_letter", String.class))
                .isEqualTo("RESOLVED");
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        store.progress(
                tenant,
                run,
                1,
                1,
                0,
                0,
                store.connection(tenant).orElseThrow().startDate(),
                today,
                1);
        store.finishRun(tenant, run, 1, 1, 0, 0, null, now.plusSeconds(1));
        assertThat(store.historyDue(tenant, today)).isFalse();
    }

    @Test
    void employeeBindingOutboxIsDurableClaimedAndRetriedWithoutRepeatingUserAction()
            throws Exception {
        var c = UUID.fromString(store.connection(tenant).orElseThrow().id());
        var run = store.startRun(tenant, actor, "ALL", "MANUAL", Instant.now());
        store.observeEmployee(
                tenant,
                c,
                run,
                json.readTree(
                        "{\"staff_id\":11,\"user_id\":22,\"ep_id\":12345,\"staff_name\":\"甲\"}"));
        var external = store.employees(tenant).getFirst();
        store.bind(
                tenant,
                actor,
                external,
                new EmployeeCandidate(91L, "EMP91", "李经理", "13800000000", "销售一部", "ACTIVE"),
                0,
                "关联");
        var now = Instant.now().plusSeconds(1);
        var job = store.bindingsDue(now).getFirst();
        assertThat(job.tenant()).isEqualTo(tenant);
        assertThat(job.update().userId()).isEqualTo("22");
        assertThat(job.update().employeeCode()).isEqualTo("EMP91");
        assertThat(job.update().revision()).isEqualTo(1);
        assertThat(store.claimBinding(job, now)).isTrue();
        assertThat(store.claimBinding(job, now)).isFalse();
        store.finishBinding(job, false, now);
        assertThat(store.bindingsDue(now.plusSeconds(10))).isEmpty();
        var retry = store.bindingsDue(now.plusSeconds(61)).getFirst();
        assertThat(retry.id()).isEqualTo(job.id());
        assertThat(retry.attempts()).isEqualTo(1);
        assertThat(store.claimBinding(retry, now.plusSeconds(61))).isTrue();
        store.finishBinding(job, true, now.plusSeconds(62));
        // 旧租约不能将重试任务误标为成功。
        assertThat(store.bindingsDue(now.plusSeconds(200))).hasSize(1);
        store.finishBinding(retry, true, now.plusSeconds(63));
        assertThat(store.bindingsDue(now.plusSeconds(500))).isEmpty();
        assertThat(store.employees(tenant).getFirst().employeeCode()).isEqualTo("EMP91");
    }

    @Test
    void bindingAndPropagationEventCommitTogetherOrBothRollback() throws Exception {
        var c = UUID.fromString(store.connection(tenant).orElseThrow().id());
        var run = store.startRun(tenant, actor, "ALL", "MANUAL", Instant.now());
        store.observeEmployee(
                tenant,
                c,
                run,
                json.readTree(
                        "{\"staff_id\":11,\"user_id\":22,\"ep_id\":12345,\"staff_name\":\"甲\"}"));
        var external = store.employees(tenant).getFirst();
        jdbc.execute("DROP TABLE integration_outbox_event");
        assertThatThrownBy(
                        () ->
                                store.bind(
                                        tenant,
                                        actor,
                                        external,
                                        new EmployeeCandidate(
                                                91L,
                                                "EMP91",
                                                "李经理",
                                                "13800000000",
                                                "销售一部",
                                                "ACTIVE"),
                                        0,
                                        "关联"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.employees(tenant).getFirst().employeeCode()).isNull();
        assertThat(store.mappedEmployee(tenant, "22")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM integration_manual_resolution", Long.class))
                .isZero();
    }

    @Test
    void stageCountsBelongToTheRunEvenWhenRawPayloadIsDeduplicatedAcrossRuns() {
        var first = store.startRun(tenant, actor, "ORDERS", "MANUAL", Instant.now());
        var second = store.startRun(tenant, actor, "ORDERS", "MANUAL", Instant.now());
        store.stages(tenant, first, new StageCounts(2, 1, 1, 0));
        store.stages(tenant, second, new StageCounts(2, 2, 0, 0));
        assertThat(store.runs(tenant))
                .filteredOn(r -> r.id().equals(first.toString()))
                .singleElement()
                .satisfies(r -> assertThat(r.stages()).isEqualTo(new StageCounts(2, 1, 1, 0)));
        assertThat(store.runs(tenant))
                .filteredOn(r -> r.id().equals(second.toString()))
                .singleElement()
                .satisfies(r -> assertThat(r.stages()).isEqualTo(new StageCounts(2, 2, 0, 0)));
        store.stages(UUID.randomUUID(), first, new StageCounts(99, 99, 0, 0));
        assertThat(store.runs(tenant))
                .filteredOn(r -> r.id().equals(first.toString()))
                .singleElement()
                .satisfies(r -> assertThat(r.stages().listSaved()).isEqualTo(2));
    }
}
