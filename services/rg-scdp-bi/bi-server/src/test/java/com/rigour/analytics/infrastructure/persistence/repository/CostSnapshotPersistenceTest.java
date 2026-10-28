package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.analytics.application.port.out.BiSourceSnapshotClient;
import com.rigour.analytics.infrastructure.persistence.mapper.*;
import com.rigour.analytics.infrastructure.persistence.scope.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

class CostSnapshotPersistenceTest {
    UUID tenant = UUID.randomUUID();
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    BiSourceSnapshotClient client;
    MybatisPlusCostSnapshotProjector projector;
    MybatisPlusCostDashboardRepository store;
    Clock clock;
    MybatisPlusCostExportRepository exports;

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:cost_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        var ddl =
                new ClassPathResource("db/migration/V27__cost_dashboard_snapshots.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace(" JSON ", " LONGTEXT ");
        jdbc.execute(ddl);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setDefaultScriptingLanguage(BiScopedLanguageDriver.class);
        for (var mapper :
                List.of(
                        CostSnapshotMapper.class,
                        CostFactMapper.class,
                        CostRefreshStateMapper.class,
                        CostExportMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        client = mock(BiSourceSnapshotClient.class);
        when(client.version(eq(tenant), anyString(), anyString())).thenReturn("1");
        when(client.page(eq(tenant), anyString(), anyString(), anyString()))
                .thenReturn(new BiSourceSnapshotClient.Page("1", List.of()));
        var rows = new ArrayList<Map<String, String>>();
        for (int i = 1; i <= 1001; i++) rows.add(row(i));
        when(client.page(tenant, "ERP", "ERP_COST_ENTRY", ""))
                .thenReturn(new BiSourceSnapshotClient.Page("1", rows.subList(0, 1000)));
        when(client.page(tenant, "ERP", "ERP_COST_ENTRY", id(1000)))
                .thenReturn(new BiSourceSnapshotClient.Page("1", rows.subList(1000, 1001)));
        var json = JsonMapper.builder().build();
        clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC);
        exports =
                new MybatisPlusCostExportRepository(
                        session.getMapper(CostExportMapper.class), clock);
        var snapshots = session.getMapper(CostSnapshotMapper.class);
        var facts = session.getMapper(CostFactMapper.class);
        var states = session.getMapper(CostRefreshStateMapper.class);
        store = new MybatisPlusCostDashboardRepository(snapshots, facts, states, json, clock);
        var proxy =
                new ProxyFactory(
                        new MybatisPlusCostSnapshotProjector(
                                snapshots,
                                facts,
                                states,
                                client,
                                new CostSourceNormalizer(json),
                                json,
                                clock));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        projector = (MybatisPlusCostSnapshotProjector) proxy.getProxy();
    }

    static String id(int n) {
        return String.format("00000000-0000-0000-0000-%012d", n);
    }

    Map<String, String> row(int n) {
        Map<String, String> row = new HashMap<>();
        CostSourceDatasets.ALL.getFirst().columns().forEach(c -> row.put(c, null));
        row.put("id", id(n));
        row.put("tenant_id", tenant.toString());
        row.put("category_code", "PURCHASE");
        row.put("amount", "0.01");
        row.put("status_code", "CONFIRMED");
        row.put("cost_date", "2026-10-16");
        row.put("revision", "1");
        row.put("data_json", "{}");
        return row;
    }

    void eligible() {
        jdbc.update(
                "UPDATE bi_cost_refresh_state SET succeeded_at=?",
                LocalDateTime.of(2026, 10, 19, 0, 0));
    }

    @AfterEach
    void close() {
        projector.close();
        ds.destroy();
    }

    @Test
    void versionReadsOverlapWithinBoundAndStillCheckBeforeAndAfterPublishing() {
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        var firstWave = new java.util.concurrent.CountDownLatch(4);
        when(client.version(eq(tenant), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            int current = active.incrementAndGet();
                            maximum.accumulateAndGet(current, Math::max);
                            firstWave.countDown();
                            try {
                                assertThat(
                                                firstWave.await(
                                                        2, java.util.concurrent.TimeUnit.SECONDS))
                                        .isTrue();
                                return "1";
                            } finally {
                                active.decrementAndGet();
                            }
                        });
        projector.refresh(tenant.toString());
        assertThat(maximum.get()).isBetween(2, 4);
        assertThat(
                        store.snapshot(
                                        tenant.toString(),
                                        store.state(tenant.toString()).snapshotId())
                                .records())
                .hasSize(1001);
        for (var dataset : CostSourceDatasets.ALL)
            verify(client, times(2)).version(tenant, dataset.source(), dataset.code());
        verify(client, times(2)).version(tenant, "HR", "HR_EMPLOYEE");
    }

    @Test
    void textUuidPagesAndTenantIsolationAndUnchangedVersionReuse() {
        projector.refresh(tenant.toString());
        var state = store.state(tenant.toString());
        store.register(tenant.toString());
        assertThat(store.state(tenant.toString())).isEqualTo(state);
        var snapshot = store.snapshot(tenant.toString(), state.snapshotId());
        assertThat(snapshot.records()).hasSize(1001);
        assertThat(snapshot.records().stream().map(r -> r.sourceId()).distinct()).hasSize(1001);
        assertThatThrownBy(() -> store.snapshot(UUID.randomUUID().toString(), state.snapshotId()))
                .hasMessageContaining("过期");
        eligible();
        projector.refresh(tenant.toString());
        assertThat(store.state(tenant.toString()).snapshotId()).isEqualTo(state.snapshotId());
        verify(client, times(1)).page(tenant, "ERP", "ERP_COST_ENTRY", "");
    }

    @Test
    void classificationUpgradeRebuildsSnapshotEvenWithinCooldownWithoutSourceChanges() {
        Map<String, String> expense = new HashMap<>();
        CostSourceDatasets.ALL.stream()
                .filter(d -> "HR_EXPENSE".equals(d.code()))
                .findFirst()
                .orElseThrow()
                .columns()
                .forEach(c -> expense.put(c, null));
        expense.put("id", id(2000));
        expense.put("tenant_id", tenant.toString());
        expense.put("type_code", "OFFICE_SUPPLIES");
        expense.put("amount", "12.00");
        expense.put("status_code", "CONFIRMED");
        expense.put("cost_date", "2026-10-16");
        expense.put("revision", "1");
        expense.put("data_json", "{}");
        when(client.page(tenant, "HR", "HR_EXPENSE", ""))
                .thenReturn(new BiSourceSnapshotClient.Page("1", List.of(expense)));
        projector.refresh(tenant.toString());
        var old = store.state(tenant.toString()).snapshotId();
        var mapper = JsonMapper.builder().build();
        var versions =
                (tools.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                jdbc.queryForObject(
                                        "SELECT versions_json FROM bi_cost_snapshot WHERE id=?",
                                        String.class,
                                        old));
        versions.remove("BI_COST_NORMALIZATION");
        jdbc.update(
                "UPDATE bi_cost_snapshot SET versions_json=? WHERE id=?",
                mapper.writeValueAsString(versions),
                old);

        projector.refresh(tenant.toString());
        var fresh = store.state(tenant.toString()).snapshotId();
        assertThat(fresh).isNotEqualTo(old);
        assertThat(
                        store.snapshot(tenant.toString(), fresh).records().stream()
                                .filter(f -> "HR".equals(f.source()))
                                .toList())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.category()).isEqualTo("expense");
                            assertThat(f.amount()).isEqualByComparingTo("12.00");
                        });
        verify(client, times(2)).page(tenant, "HR", "HR_EXPENSE", "");
        verify(client, never()).version(tenant, "BI", "BI_COST_NORMALIZATION");
    }

    @Test
    void finalVersionChangeRollsBackAllNewRowsAndKeepsPublishedPointer() {
        projector.refresh(tenant.toString());
        String old = store.state(tenant.toString()).snapshotId();
        eligible();
        when(client.version(tenant, "ERP", "ERP_COST_ENTRY")).thenReturn("1");
        jdbc.update("UPDATE bi_cost_snapshot SET versions_json='{}'");
        when(client.version(tenant, "HR", "HR_EXPENSE")).thenReturn("1", "2");
        assertThatThrownBy(() -> projector.refresh(tenant.toString()))
                .hasMessage("SOURCE_VERSION_CHANGED");
        assertThat(store.state(tenant.toString()).snapshotId()).isEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_source_cost_record", Integer.class))
                .isEqualTo(1001);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_cost_snapshot", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void badTenantAndDuplicateCursorNeverPublish() {
        var bad = row(1);
        bad.put("tenant_id", UUID.randomUUID().toString());
        when(client.page(tenant, "ERP", "ERP_COST_ENTRY", ""))
                .thenReturn(new BiSourceSnapshotClient.Page("1", List.of(bad)));
        assertThatThrownBy(() -> projector.refresh(tenant.toString())).hasMessageContaining("租户");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_cost_snapshot", Integer.class))
                .isZero();
        when(client.page(tenant, "ERP", "ERP_COST_ENTRY", ""))
                .thenReturn(new BiSourceSnapshotClient.Page("1", List.of(row(1), row(1))));
        assertThatThrownBy(() -> projector.refresh(tenant.toString())).hasMessageContaining("游标");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_source_cost_record", Integer.class))
                .isZero();
    }

    @Test
    void expiredMetadataCannotBeBypassedByWarmCache() {
        projector.refresh(tenant.toString());
        String id = store.state(tenant.toString()).snapshotId();
        store.snapshot(tenant.toString(), id);
        jdbc.update(
                "UPDATE bi_cost_snapshot SET generated_at=?", LocalDateTime.of(2026, 10, 17, 0, 0));
        assertThatThrownBy(() -> store.snapshot(tenant.toString(), id)).hasMessageContaining("过期");
    }

    @Test
    void exportOwnershipAndCancellationCannotBeOverwrittenByLateWorker() {
        var id = UUID.randomUUID().toString();
        exports.create(
                new com.rigour.analytics.application.port.out.CostExportStore.Task(
                        id,
                        tenant.toString(),
                        "actor",
                        "snapshot",
                        "hash",
                        "{}",
                        "PENDING",
                        null,
                        null,
                        null,
                        clock.instant()));
        assertThat(exports.find("another-tenant", "actor", id)).isNull();
        assertThat(exports.find(tenant.toString(), "another-actor", id)).isNull();
        assertThat(exports.start(tenant.toString(), id)).isTrue();
        exports.cancel(tenant.toString(), "actor", id);
        exports.finish(tenant.toString(), id, "sensitive csv", 100);
        var cancelled = exports.find(tenant.toString(), "actor", id);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.content()).isNull();
        jdbc.update("UPDATE bi_cost_export SET created_at=?", LocalDateTime.of(2026, 10, 17, 0, 0));
        exports.expire();
        assertThat(exports.find(tenant.toString(), "actor", id)).isNull();
    }

    @Test
    void changedSourceIsVisibleImmediatelyAndLinkedExpenseIsNeverDoubleCounted() throws Exception {
        var json = JsonMapper.builder().build();
        var fixture =
                json.readTree(
                        getClass().getResourceAsStream("/cost-dashboard/market-scenario.json"));
        Map<String, List<Map<String, String>>> data = new HashMap<>();
        for (var record : fixture.path("records")) {
            String dataset = record.path("dataset").asText();
            var def =
                    CostSourceDatasets.ALL.stream()
                            .filter(d -> d.code().equals(dataset))
                            .findFirst()
                            .orElseThrow();
            Map<String, String> row = new LinkedHashMap<>();
            def.columns().forEach(c -> row.put(c, null));
            record.path("fields")
                    .properties()
                    .forEach(
                            e -> {
                                if (row.containsKey(e.getKey()))
                                    row.put(e.getKey(), e.getValue().asText());
                            });
            row.put("tenant_id", tenant.toString());
            data.computeIfAbsent(dataset, k -> new ArrayList<>()).add(row);
        }
        for (var d : CostSourceDatasets.ALL)
            when(client.page(tenant, d.source(), d.code(), ""))
                    .thenReturn(
                            new BiSourceSnapshotClient.Page(
                                    "1", data.getOrDefault(d.code(), List.of())));
        projector.refresh(tenant.toString());
        String old = store.state(tenant.toString()).snapshotId();
        var facts = store.snapshot(tenant.toString(), old).records();
        assertThat(
                        com.rigour.analytics.domain.cost.CostAnalysis.amount(
                                facts.stream().filter(r -> r.scope().equals("opex")).toList()))
                .isEqualByComparingTo("20717.30");
        assertThat(facts.stream().filter(r -> r.treatment().equals("LINKED"))).hasSize(1);
        var changed = data.get("ORDER_LOGISTICS_COST");
        changed.getLast().put("revision", "2");
        changed.getLast().put("amount", "330.00");
        when(client.version(tenant, "ORDER", "ORDER_LOGISTICS_COST")).thenReturn("2");
        when(client.page(tenant, "ORDER", "ORDER_LOGISTICS_COST", ""))
                .thenReturn(new BiSourceSnapshotClient.Page("2", changed));
        // No timer advancement: source confirmation/change must invalidate the prior batch now.
        projector.refresh(tenant.toString());
        String fresh = store.state(tenant.toString()).snapshotId();
        assertThat(fresh).isNotEqualTo(old);
        var next = store.snapshot(tenant.toString(), fresh).records();
        assertThat(next.stream().filter(r -> r.treatment().equals("LINKED"))).isEmpty();
        assertThat(next.stream().filter(r -> r.treatment().equals("REVIEW"))).hasSize(2);
        assertThat(
                        com.rigour.analytics.domain.cost.CostAnalysis.amount(
                                next.stream().filter(r -> r.scope().equals("opex")).toList()))
                .isEqualByComparingTo("20727.30");
        assertThat(
                        com.rigour.analytics.domain.cost.CostAnalysis.amount(
                                facts.stream().filter(r -> r.scope().equals("opex")).toList()))
                .isEqualByComparingTo("20717.30");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "cost.performance",
            matches = "true")
    void isolatedDatabaseProjection10000RowsIsMeasuredWithoutTouchingSharedServices()
            throws Exception {
        for (int start = 0; start < 10000; start += 1000) {
            List<Map<String, String>> page = new ArrayList<>();
            for (int n = start + 1; n <= start + 1000; n++) page.add(row(n));
            when(client.page(tenant, "ERP", "ERP_COST_ENTRY", start == 0 ? "" : id(start)))
                    .thenReturn(new BiSourceSnapshotClient.Page("1", page));
        }
        when(client.page(tenant, "ERP", "ERP_COST_ENTRY", id(10000)))
                .thenReturn(new BiSourceSnapshotClient.Page("1", List.of()));
        long begin = System.nanoTime();
        projector.refresh(tenant.toString());
        long projected = System.nanoTime();
        var snapshot =
                store.snapshot(tenant.toString(), store.state(tenant.toString()).snapshotId());
        long read = System.nanoTime();
        assertThat(snapshot.records()).hasSize(10000);
        assertThat(com.rigour.analytics.domain.cost.CostAnalysis.amount(snapshot.records()))
                .isEqualByComparingTo("100.00");
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/cost-database-performance.json"),
                JsonMapper.builder()
                        .build()
                        .writeValueAsString(
                                Map.of(
                                        "environment",
                                        "isolated H2; mocked source HTTP; real MyBatis inserts and"
                                                + " reads",
                                        "records",
                                        10000,
                                        "projectionMs",
                                        (projected - begin) / 1000000.0,
                                        "snapshotReadMs",
                                        (read - projected) / 1000000.0)));
    }
}
