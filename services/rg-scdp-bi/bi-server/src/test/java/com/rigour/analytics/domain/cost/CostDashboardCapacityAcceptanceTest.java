package com.rigour.analytics.domain.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.api.v1.model.*;
import com.rigour.analytics.application.port.out.*;
import com.rigour.analytics.application.service.*;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Isolated capacity acceptance: the oracle never calls the dashboard's aggregation helpers. */
class CostDashboardCapacityAcceptanceTest {
    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001200");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC);
    static final List<CostFact> FACTS = fixture();
    static final Limit ALL = new Limit("ALL", List.of());
    static final Limit NONE = new Limit("NONE", List.of());
    static final String SNAPSHOT = "capacity-120000";
    CostDashboardStore store;
    CostDashboardAccess access;
    CostDashboardService service;

    @BeforeEach
    void setup() {
        store = mock(CostDashboardStore.class);
        when(store.state(TENANT.toString())).thenReturn(new CostDashboardStore.State(
                SNAPSHOT, CLOCK.instant(), CLOCK.instant(), "SUCCESS"));
        when(store.snapshot(TENANT.toString(), SNAPSHOT)).thenReturn(snapshot(SNAPSHOT, FACTS));
        access = mock(CostDashboardAccess.class);
        authorize("ALL", null, true);
        service = new CostDashboardService(store, mock(CostDashboardRefreshService.class), access,
                mock(CostSourceDetail.class), true, CLOCK);
    }

    @Test
    void reconciles120000FactsAgainstIndependentIntegerCentOracleAcrossTwelveMonths() {
        assertThat(FACTS).hasSize(120000);
        assertThat(FACTS.stream().map(CostFact::employeeCode).distinct()).hasSize(500);
        assertThat(FACTS.stream().map(CostFact::city).distinct()).hasSize(51);
        assertThat(FACTS.stream().filter(r -> "PAYROLL".equals(r.sourceCategory())).count())
                .isEqualTo(6000);
        for (int month = 1; month <= 12; month++) {
            var expected = oracle(month, "opex", null, r -> true);
            var report = service.overview(query(month, "opex", null, 1));
            assertReport(report, expected);
            assertThat(new BigDecimal(report.purchase()))
                    .isEqualByComparingTo(oracle(month, "purchase", null, r -> true).amount());
            assertThat(new BigDecimal(report.loss()))
                    .isEqualByComparingTo(oracle(month, "loss", null, r -> true).amount());
        }
        var hq = service.overview(query(10, "opex", "COST_HEADQUARTERS", 1));
        assertReport(hq, oracle(10, "opex", "COST_HEADQUARTERS", r -> true));
        assertThat(hq.cityGroups()).extracting(CostDashboardGroup::key)
                .containsExactly("COST_HEADQUARTERS");
    }

    @Test
    void allPagesRemainOnOneSnapshotAndNeverLoseOrDuplicateRecords() {
        var expected = oracle(10, "opex", null, r -> true);
        var first = service.workspace(query(10, "opex", null, 1));
        assertReport(first.report(), expected);
        assertThat(first.report().meta()).isEqualTo(first.records().meta());
        Set<String> ids = new HashSet<>();
        int pages = Math.toIntExact((expected.rows() + 99) / 100);
        for (int page = 1; page <= pages; page++) {
            var records = page == 1 ? first.records() : service.records(query(10, "opex", null, page));
            assertThat(records.meta().snapshotId()).isEqualTo(SNAPSHOT);
            assertThat(records.summary().count()).isEqualTo(expected.rows());
            assertThat(new BigDecimal(records.summary().confirmed()))
                    .isEqualByComparingTo(expected.amount());
            assertThat(new BigDecimal(records.summary().draft()))
                    .isEqualByComparingTo(expected.draft());
            assertThat(records.summary().missing()).isEqualTo(expected.missing());
            for (var row : records.items())
                assertThat(ids.add(row.source() + ":" + row.sourceId())).isTrue();
        }
        assertThat(ids).hasSize(Math.toIntExact(expected.rows()));
        assertThat(service.records(query(10, "opex", null, pages + 1)).items()).isEmpty();
    }

    @Test
    void intersectsRegionSelfSourcePermissionAndLatestVisibilityAtCapacity() {
        authorize("REGION", "CITY-01", true);
        assertReport(service.overview(query(10, "opex", null, 1)),
                oracle(10, "opex", "CITY-01", r -> true));
        assertThatThrownBy(() -> service.overview(query(10, "opex", "CITY-02", 1)))
                .isInstanceOf(AuthorizationDeniedException.class);
        authorize("SELF", null, true);
        assertReport(service.overview(query(10, "opex", null, 1)),
                oracle(10, "opex", null, r -> "E000".equals(r.employeeCode())));
        authorize("ALL", null, false);
        assertReport(service.overview(query(10, "opex", null, 1)),
                oracle(10, "opex", null, r -> !"PAYROLL".equals(r.sourceCategory())));

        authorize("ALL", null, true);
        var current = FACTS.stream().filter(r -> !"E000".equals(r.employeeCode())).toList();
        when(store.state(TENANT.toString())).thenReturn(new CostDashboardStore.State(
                "newer", CLOCK.instant(), CLOCK.instant(), "SUCCESS"));
        when(store.snapshot(TENANT.toString(), "newer")).thenReturn(snapshot("newer", current));
        var historical = service.workspace(query(10, "opex", null, 1));
        assertThat(historical.report().meta().snapshotId()).isEqualTo(SNAPSHOT);
        assertReport(historical.report(),
                oracle(10, "opex", null, r -> !"E000".equals(r.employeeCode())));
    }

    @Test
    @EnabledIfSystemProperty(named = "cost.performance", matches = "true")
    void measuresTwentyConcurrentWorkspacesWithoutReplacingTheIndependentOracle() throws Exception {
        var expected = oracle(10, "opex", null, r -> true);
        assertReport(service.workspace(query(10, "opex", null, 1)).report(), expected);
        var pool = Executors.newFixedThreadPool(20);
        var ready = new CountDownLatch(20);
        var start = new CountDownLatch(1);
        var jobs = new ArrayList<Future<Double>>();
        try {
            for (int i = 0; i < 20; i++) {
                final int page = i % 5 + 1;
                jobs.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    long began = System.nanoTime();
                    var result = service.workspace(query(10, "opex", null, page));
                    assertReport(result.report(), expected);
                    assertThat(result.records().summary().count()).isEqualTo(expected.rows());
                    assertThat(result.records().meta()).isEqualTo(result.report().meta());
                    return (System.nanoTime() - began) / 1_000_000.0;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var times = new ArrayList<Double>();
            for (var job : jobs) times.add(job.get(120, TimeUnit.SECONDS));
            times.sort(Double::compare);
            var result = new LinkedHashMap<String, Object>();
            result.put("measurementLayer", "isolated Java service; mocked immutable snapshot store and online IAM; no HTTP/MySQL/source refresh");
            result.put("facts", FACTS.size());
            result.put("operatingCities", 50);
            result.put("headquarters", true);
            result.put("employees", 500);
            result.put("months", 12);
            result.put("concurrentWorkspaces", 20);
            result.put("independentlyExpectedOctoberOpex", expected.amount().toPlainString());
            result.put("independentlyExpectedOctoberLedgerRows", expected.rows());
            result.put("p50Ms", times.get(9));
            result.put("p95Ms", times.get(18));
            result.put("maxMs", times.get(19));
            result.put("errors", 0);
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/cost-dashboard-capacity-acceptance.json"),
                    JsonMapper.builder().build().writeValueAsString(result));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private void authorize(String mode, String city, boolean includePayroll) {
        Limit regions = city == null ? ALL : new Limit("SPECIFIED", List.of(city));
        var dashboard = policy("ANALYTICS", mode, regions);
        var sources = new HashMap<String, SupplyAuthorizationView>();
        for (var category : CostDashboardAccess.ACTIONS.keySet()) {
            if (!includePayroll && "labor".equals(category)) continue;
            sources.put(category, policy(Set.of("labor", "expense", "marketing").contains(category)
                    ? "EMPLOYEE" : "INVENTORY", "ALL", ALL));
        }
        var actor = new CallerIdentity("TENANT", USER, TENANT, USER, null,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                1, 1, 1, Set.of(), Set.of("*:*:*"));
        when(access.current()).thenReturn(new CostDashboardAccess.Access(
                actor, dashboard, Map.copyOf(sources), dashboard, "capacity-policy-" + mode));
    }

    private static SupplyAuthorizationView policy(String object, String mode, Limit regions) {
        return new SupplyAuthorizationView("ACTIVE", TENANT, USER, "E000", 1, 1, 1, 1,
                Set.of("*:*:*"), "action", true,
                List.of(new Clause(UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        object, mode, NONE, "REGION".equals(mode) ? regions : NONE, ALL, true)),
                regions, ALL);
    }

    private static CostDashboardStore.Snapshot snapshot(String id, List<CostFact> rows) {
        return new CostDashboardStore.Snapshot(id, TENANT.toString(), CLOCK.instant(),
                LocalDate.of(2026, 10, 23), Map.of("BI_COST_NORMALIZATION", "6"), List.of(), rows);
    }

    private static CostDashboardQuery query(int month, String scope, String city, int page) {
        return new CostDashboardQuery(2025, month, "previous", city, scope,
                null, null, null, "ALL", "ALL", null, "current", page, 100, SNAPSHOT, false);
    }

    private record Expected(long cents, long count, long rows, long draftCents, long missing,
            long linkedCents, Map<String, Long> categoryCents) {
        BigDecimal amount() { return BigDecimal.valueOf(cents, 2); }
        BigDecimal draft() { return BigDecimal.valueOf(draftCents, 2); }
    }

    private static Expected oracle(int month, String scope, String city, Predicate<CostFact> visible) {
        long cents = 0, count = 0, rows = 0, draft = 0, missing = 0, linked = 0;
        String wantedMonth = String.format(Locale.ROOT, "2025-%02d", month);
        var categoryCents = new HashMap<String, Long>();
        for (var row : FACTS) {
            if (!visible.test(row) || city != null && !city.equals(row.city())) continue;
            boolean goods = "purchase".equals(row.category()) || "loss".equals(row.category());
            if (!(goods ? row.category() : "opex").equals(scope)) continue;
            // Independently interpret saved monthly allocation instead of calling inPeriod/costMonth.
            String allocated = row.dimensions().containsKey("costMonth")
                    ? row.dimensions().get("costMonth").key() : row.date().toString().substring(0, 7);
            if (!allocated.equals(wantedMonth)) continue;
            rows++;
            long value = row.amount() == null ? 0 : row.amount().movePointRight(2).longValueExact();
            String treatment = row.dimensions().containsKey("costTreatment")
                    ? row.dimensions().get("costTreatment").key() : "DIRECT";
            if (row.amount() == null && !"VOID".equals(row.status())) missing++;
            if ("DRAFT".equals(row.status())) draft += value;
            if (!"CONFIRMED".equals(row.status())) continue;
            if ("LINKED".equals(treatment)) linked += value;
            if (row.amount() == null || !"DIRECT".equals(treatment)) continue;
            cents += value;
            count++;
            categoryCents.merge(row.category(), value, Long::sum);
        }
        return new Expected(cents, count, rows, draft, missing, linked, categoryCents);
    }

    private static void assertReport(CostDashboardReport report, Expected expected) {
        assertThat(new BigDecimal(report.current().amount())).isEqualByComparingTo(expected.amount());
        assertThat(report.current().count()).isEqualTo(expected.count());
        assertThat(new BigDecimal(report.current().linkedAmount()))
                .isEqualByComparingTo(BigDecimal.valueOf(expected.linkedCents(), 2));
        long categorySum = 0;
        for (var category : report.categories()) {
            long actual = new BigDecimal(category.current()).movePointRight(2).longValueExact();
            assertThat(actual).isEqualTo(expected.categoryCents().getOrDefault(category.key(), 0L));
            categorySum += actual;
        }
        assertThat(categorySum).isEqualTo(expected.cents());
    }

    private static List<CostFact> fixture() {
        var result = new ArrayList<CostFact>(120000);
        for (int month = 1; month <= 12; month++)
            for (int employee = 0; employee < 500; employee++)
                result.add(fact("payroll-" + month + "-" + employee, "labor", month, employee,
                        BigDecimal.valueOf(600000L + employee * 100L, 2), "CONFIRMED", false, false));
        String[] categories = {"warehouse", "office", "freight", "marketing", "expense", "purchase", "loss"};
        for (int i = 0; i < 114000; i++) {
            String category = categories[i % categories.length];
            String status = i % 17 == 0 ? "VOID" : i % 19 == 0 ? "DRAFT" : "CONFIRMED";
            var amount = i % 37 == 0 ? null : BigDecimal.valueOf(1000L + (i * 37L) % 990001L, 2);
            result.add(fact("cost-" + i, category, i / 9500 + 1, (i * 17) % 500, amount,
                    status, "expense".equals(category) && i % 31 == 0, i % 3 == 0));
        }
        return List.copyOf(result);
    }

    private static CostFact fact(String id, String category, int month, int employee,
            BigDecimal amount, String status, boolean linked, boolean paid) {
        String code = String.format(Locale.ROOT, "E%03d", employee);
        String city = employee == 0 ? "COST_HEADQUARTERS" : String.format(Locale.ROOT, "CITY-%02d", employee % 50 + 1);
        String department = "D-" + city;
        String source = Set.of("labor", "expense").contains(category) ? "HR"
                : "freight".equals(category) ? "ORDER" : "ERP";
        String sourceCategory = "labor".equals(category) ? "PAYROLL" : category.toUpperCase(Locale.ROOT);
        var dimensions = new HashMap<String, CostFact.Dimension>();
        dimensions.put("employee", new CostFact.Dimension(code, "员工" + employee, false));
        dimensions.put("department", new CostFact.Dimension(department, department, false));
        dimensions.put("authorizationEmployeeDepartment", new CostFact.Dimension(department, department, false));
        dimensions.put("costTreatment", new CostFact.Dimension(linked ? "LINKED" : "DIRECT", "计入方式", false));
        boolean monthly = Set.of("labor", "office", "warehouse").contains(category);
        var ym = YearMonth.of(2025, month);
        if (monthly) {
            dimensions.put("periodBasis", new CostFact.Dimension("MONTH", "按费用所属月", false));
            dimensions.put("costMonth", new CostFact.Dimension(ym.toString(), ym.toString(), false));
        }
        if (linked) dimensions.put("linkedSourceId", new CostFact.Dimension("original-" + id, "原运输单", false));
        String creator = employee == 0 ? USER.toString() : UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        Map<String, BigDecimal> components = "labor".equals(category)
                ? Map.of("baseSalary", amount.subtract(new BigDecimal("800.00")),
                        "performance", new BigDecimal("500.00"), "commission", new BigDecimal("300.00"))
                : Map.of();
        return new CostFact(source, id, "source_" + category, "单据-" + id, "业务事项-" + id,
                category, sourceCategory, city, "COST_HEADQUARTERS".equals(city) ? "总部" : city,
                city, "MAPPED", monthly ? ym.minusMonths(1).atEndOfMonth() : ym.atDay(employee % 28 + 1),
                amount, status, paid ? "PAID" : "UNPAID", "MANUAL", code, "员工" + employee,
                department, List.of(department), "W-" + city, "W-" + city, creator, 1L,
                "模拟经营场景；隔离内存数据", Map.copyOf(dimensions), List.of(), components);
    }
}
