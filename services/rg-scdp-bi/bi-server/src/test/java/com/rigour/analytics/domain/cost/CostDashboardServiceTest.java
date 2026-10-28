package com.rigour.analytics.domain.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.api.v1.model.CostDashboardQuery;
import com.rigour.analytics.application.port.out.*;
import com.rigour.analytics.application.service.*;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class CostDashboardServiceTest {
    final UUID tenant = UUID.randomUUID(),
            user = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final Clock clock = Clock.fixed(Instant.parse("2026-10-16T06:00:00Z"), ZoneOffset.UTC);
    CostDashboardStore store;
    CostDashboardAccess access;
    CostDashboardService service;
    List<CostFact> facts;
    CallerIdentity actor;
    Map<String, SupplyAuthorizationView> sources;
    SupplyAuthorizationView all;

    SupplyAuthorizationView policy(String object, String mode, Limit region) {
        var any = new Limit("ALL", List.of());
        var none = new Limit("NONE", List.of());
        return new SupplyAuthorizationView(
                "ACTIVE",
                tenant,
                user,
                "E1",
                1,
                1,
                1,
                1,
                Set.of("*:*:*"),
                "action",
                true,
                List.of(new Clause(UUID.randomUUID(), object, mode, none, region, any, true)),
                any,
                any);
    }

    @BeforeEach
    void setup() throws Exception {
        var json = JsonMapper.builder().build();
        try (var in = getClass().getResourceAsStream("/cost-dashboard/v5-facts.json")) {
            facts =
                    json.readValue(
                            in,
                            json.getTypeFactory()
                                    .constructCollectionType(List.class, CostFact.class));
        }
        actor =
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of(),
                        Set.of("*:*:*"));
        all = policy("ANALYTICS", "ALL", new Limit("ALL", List.of()));
        sources = new HashMap<>();
        for (String cat : CostDashboardAccess.ACTIONS.keySet())
            sources.put(
                    cat,
                    policy(
                            Set.of("expense", "marketing", "labor").contains(cat)
                                    ? "EMPLOYEE"
                                    : "INVENTORY",
                            "ALL",
                            new Limit("ALL", List.of())));
        access = mock(CostDashboardAccess.class);
        authorize(all);
        store = mock(CostDashboardStore.class);
        when(store.state(tenant.toString()))
                .thenReturn(
                        new CostDashboardStore.State(
                                "snapshot", clock.instant(), clock.instant(), "SUCCESS"));
        when(store.snapshot(tenant.toString(), "snapshot"))
                .thenReturn(
                        new CostDashboardStore.Snapshot(
                                "snapshot",
                                tenant.toString(),
                                clock.instant(),
                                LocalDate.of(2026, 10, 16),
                                Map.of("BI_COST_NORMALIZATION", "6"),
                                List.of(),
                                facts));
        service =
                new CostDashboardService(
                        store,
                        mock(CostDashboardRefreshService.class),
                        access,
                        mock(CostSourceDetail.class),
                        true,
                        clock);
    }

    void authorize(SupplyAuthorizationView export) {
        when(access.current())
                .thenReturn(
                        new CostDashboardAccess.Access(
                                actor, all, Map.copyOf(sources), export, "policy"));
    }

    CostDashboardQuery query(String city, String category, String side, String payment) {
        return new CostDashboardQuery(
                2026,
                10,
                "previous",
                city,
                "opex",
                category,
                null,
                null,
                "CONFIRMED",
                payment,
                null,
                side,
                1,
                20,
                null,
                false);
    }

    @Test
    void recordsWorkspaceUsesOneAuthorizationAndOneCompleteSnapshot() {
        authorize(null);
        var q = query(null, null, "current", "ALL");
        var workspace = service.workspace(q);
        verify(access, times(1)).current();
        assertThat(workspace.records().meta()).isEqualTo(workspace.report().meta());
        assertThat(workspace.records()).isEqualTo(service.records(q));
        assertThat(workspace.report()).isEqualTo(service.overview(q));
    }

    @Test
    void hourlySnapshotRemainsReadyUntilAnActualRefreshFails() {
        var lastSuccess = clock.instant().minus(Duration.ofMinutes(45));
        when(store.state(tenant.toString()))
                .thenReturn(
                        new CostDashboardStore.State(
                                "snapshot", lastSuccess, lastSuccess, "SUCCESS"));
        assertThat(service.context(query(null, null, "current", "ALL")).meta().dataState())
                .isEqualTo("READY");
        when(store.state(tenant.toString()))
                .thenReturn(
                        new CostDashboardStore.State(
                                "snapshot", clock.instant(), lastSuccess, "FAILED"));
        assertThat(service.context(query(null, null, "current", "ALL")).meta().dataState())
                .isEqualTo("STALE");
    }

    @Test
    void approvedV5FiguresAndAllBreakdownsReconcile() {
        var r = service.overview(query(null, null, "current", "ALL"));
        assertThat(r.current().amount()).isEqualTo("180048.71");
        assertThat(r.delta()).isEqualTo("5479.71");
        assertThat(new BigDecimal(r.increased()).subtract(new BigDecimal(r.decreased())))
                .isEqualByComparingTo(r.delta());
        assertThat(
                        r.categories().stream()
                                .map(g -> new BigDecimal(g.current()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(r.current().amount());
        assertThat(r.cumulative().getLast().current()).isEqualTo(r.current().amount());
        assertThat(r.majorRecords()).hasSize(8);
        assertThat(r.majorRecords())
                .extracting(x -> new BigDecimal(x.amount()))
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(r.purchase()).isNotEqualTo("0.00");
        assertThat(r.loss()).isNotEqualTo("0.00");
        var office = service.overview(query("武汉市", "office", "current", "ALL"));
        assertThat(office.current().amount()).isEqualTo("4422.60");
        assertThat(office.previous().amount()).isEqualTo("4876.95");
        var marketing = service.overview(query("成都市", "marketing", "current", "ALL"));
        assertThat(marketing.delta()).isEqualTo("6165.69");
    }

    @Test
    void ledgerFiltersDoNotChangeOverviewAndTotalsAreNotCurrentPage() {
        var allRows = service.records(query(null, null, "current", "ALL"));
        var unpaid = service.records(query(null, null, "current", "UNPAID"));
        assertThat(allRows.summary().count()).isGreaterThan(20);
        assertThat(allRows.items()).hasSize(20);
        assertThat(allRows.summary().confirmed()).isEqualTo("180048.71");
        assertThat(unpaid.summary().count()).isLessThan(allRows.summary().count());
        assertThat(service.overview(query(null, null, "current", "UNPAID")).current().amount())
                .isEqualTo("180048.71");
        assertThat(service.records(query("武汉市", "office", "previous", "ALL")).summary().confirmed())
                .isEqualTo("4876.95");
    }

    @Test
    void newCategoryAndCityKeepTheParentComparisonWithZeroPriorCostAndNoGrowthRate() {
        var normalizer = new com.rigour.analytics.infrastructure.persistence.scope.CostSourceNormalizer(
                JsonMapper.builder().build());
        var rent = normalizer.normalize("ERP", Map.of("id", "new-rent", "amount", "6000.00",
                        "status_code", "CONFIRMED", "data_json", "{}", "cost_date", "2026-10-01",
                        "revision", "1", "category_code", "RENTAL", "premises_type", "WAREHOUSE",
                        "city_code", "WH"),
                        List.of(new CostDashboardStore.City("WH", "武汉市", "WH", "20", List.of("20"))), Map.of());
        useFacts(List.of(rent, salary("E1", false)));
        var parent = service.overview(query(null, null, "current", "ALL"));
        var child = service.overview(query(rent.city(), "warehouse", "current", "ALL"));
        assertThat(parent.meta().comparisonAvailable()).isTrue();
        assertThat(child.meta().comparisonAvailable()).isTrue();
        assertThat(child.current().amount()).isEqualTo("6000.00");
        assertThat(child.previous().amount()).isEqualTo("0.00");
        assertThat(child.delta()).isEqualTo("6000.00");
        assertThat(child.growth()).isNull();
    }

    @Test
    void everyDashboardEntryPointOnlyIncludesConfirmedCostsAndPreservesSourcePayment() {
        var normalizer = new com.rigour.analytics.infrastructure.persistence.scope.CostSourceNormalizer(
                JsonMapper.builder().build());
        var rows = new ArrayList<CostFact>();
        for (String source : List.of("ERP", "ORDER", "HR")) {
            for (String status : List.of("CONFIRMED", "DRAFT", "VOID")) {
                for (String payment : List.of("PAID", "UNPAID")) {
                    Map<String, String> row = new HashMap<>();
                    row.put("id", source + "-" + status + "-" + payment);
                    row.put("document_no", row.get("id"));
                    row.put("title", "测试费用");
                    row.put("amount", "100.00");
                    row.put("status_code", status);
                    row.put("payment_status_code", payment);
                    row.put("cost_date", "2026-10-01");
                    row.put("revision", "1");
                    row.put("category_code", "MARKETING");
                    row.put("payer_code", "COMPANY");
                    row.put("data_json", "{}");
                    rows.add(normalizer.normalize(source, row, List.of(), Map.of()));
                }
            }
        }
        useFacts(rows);
        var q = query(null, null, "current", "ALL");
        var c = service.context(q);
        assertThat(c.rows()).hasSize(6).allMatch(r -> "CONFIRMED".equals(r.status()));
        assertThat(service.overview(q).current().amount()).isEqualTo("600.00");
        assertThat(service.overview(q).current().unpaid()).isEqualTo("300.00");
        assertThat(service.overview(q).current().draftCount()).isZero();
        assertThat(service.records(q).items()).hasSize(6);
        assertThat(service.records(query(null, null, "current", "PAID")).items())
                .hasSize(3).allMatch(r -> "PAID".equals(r.payment()));
        assertThat(service.records(query(null, null, "current", "UNPAID")).items())
                .hasSize(3).allMatch(r -> "UNPAID".equals(r.payment()));
        var csv = CostDashboardExportService.csv(c, service.matching(c));
        assertThat(csv).doesNotContain("DRAFT", "VOID", "待确认", "已作废");
        assertThatThrownBy(() -> service.detail(q, "ERP", "ERP-DRAFT-PAID"))
                .hasMessageContaining("费用不存在或无访问权限");
    }

    @Test
    void removingExpensePermissionRemovesItsAmountsFromEveryAggregate() {
        sources.remove("expense");
        authorize(null);
        var r = service.overview(query(null, null, "current", "ALL"));
        assertThat(r.meta().excludedScopes()).contains("员工报销");
        assertThat(r.meta().exportAllowed()).isFalse();
        assertThat(r.categories()).noneMatch(g -> g.key().equals("expense"));
        assertThat(service.records(query(null, null, "current", "ALL")).items())
                .noneMatch(x -> x.category().equals("expense"));
        assertThatThrownBy(() -> service.overview(query(null, "expense", "current", "ALL")))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void absentHistorySuppressesChangesAndDoesNotCreateZeroLine() {
        var only = facts.stream().filter(r -> r.date().getMonthValue() == 10).toList();
        when(store.snapshot(tenant.toString(), "snapshot"))
                .thenReturn(
                        new CostDashboardStore.Snapshot(
                                "snapshot",
                                tenant.toString(),
                                clock.instant(),
                                LocalDate.of(2026, 10, 16),
                                Map.of("BI_COST_NORMALIZATION", "6"),
                                List.of(),
                                only));
        var r = service.overview(query(null, null, "current", "ALL"));
        assertThat(r.meta().comparisonAvailable()).isFalse();
        assertThat(r.delta()).isNull();
        assertThat(r.changes()).isEmpty();
        assertThat(r.cumulative()).allMatch(p -> p.previous() == null);
    }

    @Test
    void independentExportScopeCannotExpandReadScope() {
        authorize(policy("ANALYTICS", "REGION", new Limit("SPECIFIED", List.of("武汉市"))));
        var a = access.current();
        var other = facts.stream().filter(r -> r.city().equals("成都市")).findFirst().orElseThrow();
        assertThat(a.visible(other)).isTrue();
        assertThat(a.exportVisible(other)).isFalse();
        var export =
                new CostDashboardExportService(
                        service,
                        access,
                        mock(CostExportStore.class),
                        JsonMapper.builder().build(),
                        clock,
                        true);
        try {
            assertThatThrownBy(() -> export.create(query(null, null, "current", "ALL")))
                    .isInstanceOf(AuthorizationDeniedException.class);
        } finally {
            export.close();
        }
    }

    @Test
    void csvCellsPreventFormulaExecutionAndPreserveQuotes() {
        assertThat(CostDashboardExportService.cell(" =SUM(A1)")).isEqualTo("\"' =SUM(A1)\"");
        assertThat(CostDashboardExportService.cell("a\"b")).isEqualTo("\"a\"\"b\"");
        var c = service.context(query(null, null, "current", "ALL"));
        var text = CostDashboardExportService.csv(c, service.matching(c));
        assertThat(text).contains("180048.71", "单据号", "付款状态");
        assertThat(text).doesNotContain("来源服务", "源ID", "快照", "指标口径", "成本处理", "计入成本金额", "cost-v9");
        assertThat(text.split("\r\n")).hasSize(service.matching(c).size() + 3);
    }

    @Test
    void serializedHttpContractKeepsMoneyAsTextAndProducesIsolatedPreviewFixture()
            throws Exception {
        var query = query(null, null, "current", "ALL");
        var report = service.overview(query);
        var json = JsonMapper.builder().build();
        var contract = json.valueToTree(report);
        assertThat(contract.path("current").path("amount").isString()).isTrue();
        assertThat(contract.path("meta").path("snapshotId").asText()).isEqualTo("snapshot");
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/cost-dashboard-preview.json"),
                json.writeValueAsString(
                        Map.of("report", report, "records", service.records(query))));
    }

    @Test
    void currentAuthorizationFingerprintIncludesIndependentExportPolicy() {
        TestAuthorizationContext.set(actor);
        var policyAccess = new CostDashboardAccess(JsonMapper.builder().build());
        try (var ignored =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.open(
                        (identity, action) ->
                                new SupplyAuthorizationView(
                                        all.mode(),
                                        all.tenantId(),
                                        all.userId(),
                                        all.employeeCode(),
                                        all.applicationVersion(),
                                        all.memberVersion(),
                                        all.employeeRevision(),
                                        all.organizationVersion(),
                                        all.permissions(),
                                        action,
                                        all.functionAllowed(),
                                        all.clauses(),
                                        all.regionLimit(),
                                        all.warehouseLimit()),
                        actor,
                        all)) {
            var actual = policyAccess.current();
            assertThat(actual.hash()).hasSize(64);
            assertThat(actual.exportAllowed()).isTrue();
            assertThat(actual.categories())
                    .containsExactlyInAnyOrderElementsOf(CostAnalysis.CATEGORIES.keySet());
        } finally {
            policyAccess.close();
            TestAuthorizationContext.clear();
        }
    }

    @Test
    void earliestSelectableMonthDoesNotQueryUnsupportedTrailingYear() {
        var q =
                new CostDashboardQuery(
                        2000,
                        1,
                        "previous",
                        null,
                        "opex",
                        null,
                        null,
                        null,
                        "CONFIRMED",
                        "ALL",
                        null,
                        "current",
                        1,
                        20,
                        null,
                        false);
        var result = service.overview(q);
        assertThat(result.months()).hasSize(1);
        assertThat(result.current().amount()).isEqualTo("0.00");
    }

    @Test
    void officeReimbursementStillRequiresHrPermissionAndNeverBorrowsErpPermission() {
        var row =
                new CostFact(
                        "HR",
                        "expense-office",
                        "hr_expense",
                        "E1",
                        "办公耗材",
                        "office",
                        null,
                        "武汉市",
                        "武汉市",
                        "武汉市",
                        "CITY_CODE",
                        LocalDate.of(2026, 10, 1),
                        new BigDecimal("100"),
                        "CONFIRMED",
                        "UNKNOWN",
                        null,
                        "E1",
                        "员工",
                        "10",
                        List.of("10"),
                        null,
                        null,
                        user.toString(),
                        1L,
                        "",
                        Map.of(),
                        List.of());
        var both = new CostDashboardAccess.Access(actor, all, Map.copyOf(sources), null, "both");
        assertThat(both.visible(row)).isTrue();
        sources.remove("expense");
        var erpOnly = new CostDashboardAccess.Access(actor, all, Map.copyOf(sources), null, "erp");
        assertThat(erpOnly.visible(row)).isFalse();
        var hrOnly =
                new CostDashboardAccess.Access(
                        actor,
                        all,
                        Map.of("expense", policy("EMPLOYEE", "ALL", new Limit("ALL", List.of()))),
                        null,
                        "hr");
        assertThat(hrOnly.categories())
                .contains("expense", "office", "freight")
                .doesNotContain("labor");
        assertThat(hrOnly.visible(row)).isTrue();
    }

    @Test
    void hrCostRespectsSourceRegionLimitEvenWhenDashboardAndEmployeeAreUnrestricted() {
        var base = policy("EMPLOYEE", "ALL", new Limit("ALL", List.of()));
        var wh = new SupplyAuthorizationView(base.mode(), base.tenantId(), base.userId(),
                base.employeeCode(), base.applicationVersion(), base.memberVersion(),
                base.employeeRevision(), base.organizationVersion(), base.permissions(), base.action(),
                base.functionAllowed(), base.clauses(), new Limit("SPECIFIED", List.of("WH")),
                base.warehouseLimit());
        var row = mock(CostFact.class);
        when(row.source()).thenReturn("HR");
        when(row.employeeCode()).thenReturn("E1");
        when(row.departmentPath()).thenReturn(List.of());
        when(row.city()).thenReturn("COST_HEADQUARTERS");
        when(row.rawCity()).thenReturn("COST_HEADQUARTERS");
        assertThat(CostDashboardAccess.sourceVisible(wh, row, user.toString())).isFalse();
        when(row.city()).thenReturn("WH");
        when(row.rawCity()).thenReturn("WH");
        assertThat(CostDashboardAccess.sourceVisible(wh, row, user.toString())).isTrue();
        when(row.city()).thenReturn("COST_HEADQUARTERS");
        when(row.rawCity()).thenReturn("COST_HEADQUARTERS");
        assertThat(CostDashboardAccess.sourceVisible(base, row, user.toString())).isTrue();
    }

    CostFact salary(String employee, boolean missingCommission) {
        Map<String, String> row = new HashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("amount", "5000.03");
        row.put("status_code", "CONFIRMED");
        row.put("data_json", "{}");
        row.put("cost_date", "2026-09-30");
        row.put("revision", "2");
        row.put("category_code", "PAYROLL");
        row.put("employee_code", employee);
        row.put("employee_name", "工资测试员工");
        row.put("department_id", "10");
        row.put("department_name", "测试部门");
        row.put("document_no", "2026-09/" + employee);
        row.put("title", "工资测试员工 " + employee + " 9月薪酬");
        row.put(
                "components_json",
                missingCommission
                        ? "{\"baseSalary\":4000.01,\"performance\":500,\"allowance\":0,\"correctionAmount\":0}"
                        : "{\"baseSalary\":4000.01,\"performance\":500,\"commission\":500.02,\"allowance\":0,\"correctionAmount\":0}");
        return new com.rigour.analytics.infrastructure.persistence.scope.CostSourceNormalizer(
                        JsonMapper.builder().build())
                .normalize("HR", row, List.of(), Map.of("10", List.of("1", "10")));
    }

    void useFacts(List<CostFact> rows) {
        when(store.snapshot(tenant.toString(), "snapshot"))
                .thenReturn(
                        new CostDashboardStore.Snapshot(
                                "snapshot",
                                tenant.toString(),
                                clock.instant(),
                                LocalDate.of(2026, 10, 16),
                                Map.of("BI_COST_NORMALIZATION", "6"),
                                List.of(),
                                rows));
    }

    CostDashboardQuery september(String category) {
        return new CostDashboardQuery(
                2026,
                9,
                "previous",
                null,
                "opex",
                category,
                null,
                null,
                "CONFIRMED",
                "ALL",
                null,
                "current",
                1,
                20,
                null,
                false);
    }

    @Test
    void payrollComponentsReconcileAndIncentiveIsNotCountedAgain() {
        useFacts(List.of(salary("E1", false)));
        var report = service.overview(september("labor"));
        assertThat(report.current().amount()).isEqualTo("5000.03");
        assertThat(report.current().unpaid()).isEqualTo("0.00");
        assertThat(report.meta().comparisonAvailable()).isFalse();
        assertThat(report.payrollComponents()).hasSize(5);
        assertThat(
                        report.payrollComponents().stream()
                                .map(c -> new BigDecimal(c.amount()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(report.current().amount());
        assertThat(
                        report.payrollComponents().stream()
                                .filter(c -> c.key().equals("commission"))
                                .findFirst()
                                .orElseThrow()
                                .label())
                .isEqualTo("提成（含激励）");
        assertThat(report.payrollComponents()).noneMatch(c -> c.key().equals("incentive"));
    }

    @Test
    void missingSalaryComponentIsUnknownAndExpensePermissionNeverGrantsPayroll() {
        var row = salary("E1", true);
        useFacts(List.of(row));
        var report = service.overview(september("labor"));
        var commission =
                report.payrollComponents().stream()
                        .filter(c -> c.key().equals("commission"))
                        .findFirst()
                        .orElseThrow();
        assertThat(commission.amount()).isNull();
        assertThat(commission.missingCount()).isEqualTo(1);
        sources.remove("labor");
        authorize(null);
        var limited = service.overview(september(null));
        assertThat(limited.current().amount()).isEqualTo("0.00");
        assertThat(limited.payrollComponents()).isEmpty();
        assertThat(limited.meta().excludedScopes()).contains("人力薪酬");
        assertThat(service.records(september(null)).items()).isEmpty();
        assertThatThrownBy(() -> service.overview(september("labor")))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void selfAndDepartmentPayrollScopesMatchHrOriginalRecordRules() {
        var self = policy("EMPLOYEE", "SELF", new Limit("ALL", List.of()));
        assertThat(CostDashboardAccess.sourceVisible(self, salary("E1", false), user.toString()))
                .isTrue();
        assertThat(CostDashboardAccess.sourceVisible(self, salary("E2", false), user.toString()))
                .isFalse();
        var any = new Limit("ALL", List.of());
        var none = new Limit("NONE", List.of());
        var department =
                new SupplyAuthorizationView(
                        "ACTIVE",
                        tenant,
                        user,
                        "E1",
                        1,
                        1,
                        1,
                        1,
                        Set.of(),
                        "read",
                        true,
                        List.of(
                                new Clause(
                                        UUID.randomUUID(),
                                        "EMPLOYEE",
                                        "DEPARTMENT",
                                        new Limit("SPECIFIED", List.of("1")),
                                        none,
                                        any,
                                        true)),
                        any,
                        any);
        assertThat(
                        CostDashboardAccess.sourceVisible(
                                department, salary("E2", false), user.toString()))
                .isTrue();
    }

    @Test
    void selfDashboardIncludesOwnManualRentButStillIntersectsSourcePermissions() {
        var self = policy("ANALYTICS","SELF",new Limit("ALL",List.of()));
        var inventory = policy("INVENTORY","SELF",new Limit("ALL",List.of()));
        var own = mock(CostFact.class);
        when(own.source()).thenReturn("ERP");
        when(own.category()).thenReturn("office");
        when(own.city()).thenReturn("COST_HEADQUARTERS");
        when(own.rawCity()).thenReturn("COST_HEADQUARTERS");
        when(own.createdBy()).thenReturn(user.toString());
        var limited = new CostDashboardAccess.Access(actor,self,Map.of("office",inventory),self,"self");
        assertThat(limited.visible(own)).isTrue();
        assertThat(limited.exportVisible(own)).isTrue();
        when(own.createdBy()).thenReturn(UUID.randomUUID().toString());
        assertThat(limited.visible(own)).isFalse();
        assertThat(limited.exportVisible(own)).isFalse();
        when(own.employeeCode()).thenReturn("E1");
        // 员工维度符合看板本人范围，也不能越过原台账的创建人范围。
        assertThat(limited.visible(own)).isFalse();
        var noSource = new CostDashboardAccess.Access(actor,self,Map.of(),self,"none");
        when(own.createdBy()).thenReturn(user.toString());
        assertThat(noSource.visible(own)).isFalse();
    }

    @Test
    void selfCreatorCannotSeeAnotherEmployeesPayrollOrExpenseThroughDashboard() {
        var self = policy("ANALYTICS","SELF",new Limit("ALL",List.of()));
        var employee = policy("EMPLOYEE","SELF",new Limit("ALL",List.of()));
        var row = mock(CostFact.class);
        when(row.source()).thenReturn("HR");
        when(row.sourceCategory()).thenReturn("PAYROLL");
        when(row.employeeCode()).thenReturn("E2");
        when(row.createdBy()).thenReturn(user.toString());
        var limited = new CostDashboardAccess.Access(actor,self,Map.of("labor",employee,"expense",employee),self,"self");
        assertThat(limited.visible(row)).isFalse();
        when(row.sourceCategory()).thenReturn("EXPENSE");
        assertThat(limited.visible(row)).isFalse();
    }

    @Test
    void legacySnapshotCannotBeDisplayedWithNewPayrollLabels() {
        when(store.snapshot(tenant.toString(), "snapshot"))
                .thenReturn(
                        new CostDashboardStore.Snapshot(
                                "snapshot",
                                tenant.toString(),
                                clock.instant(),
                                LocalDate.of(2026, 10, 16),
                                Map.of("BI_COST_NORMALIZATION", "2"),
                                List.of(),
                                facts));
        assertThatThrownBy(() -> service.overview(september(null))).hasMessageContaining("口径正在准备");
    }

    @Test
    void payrollDetailUsesHrOriginalAndStopsOnChangedSourceRevision() {
        var row = salary("E1", false);
        useFacts(List.of(row));
        var original = mock(CostSourceDetail.class);
        when(original.verify("HR", "PAYROLL", row.sourceId()))
                .thenReturn(new CostSourceDetail.State("CONFIRMED", row.revision()));
        var linked =
                new CostDashboardService(
                        store,
                        mock(CostDashboardRefreshService.class),
                        access,
                        original,
                        true,
                        clock);
        var detail = linked.detail(september("labor"), "HR", row.sourceId());
        assertThat(detail.sourceRoute())
                .isEqualTo("/supply-chain/hr/payroll?sourceId=" + row.sourceId());
        assertThat(detail.dimensions()).containsEntry("提成含激励（元）", "500.02");
        when(original.verify("HR", "PAYROLL", row.sourceId()))
                .thenReturn(new CostSourceDetail.State("CONFIRMED", row.revision() + 1));
        assertThatThrownBy(() -> linked.detail(september("labor"), "HR", row.sourceId()))
                .hasMessageContaining("源单据已更新");
    }

    CostFact crossMonthRent() {
        Map<String, String> row = new HashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("amount", "6000.00");
        row.put("status_code", "CONFIRMED");
        row.put("data_json", "{\"costMonth\":\"2026-10\",\"rental\":{\"premisesName\":\"测试中心仓\"}}");
        row.put("cost_date", "2026-09-30");
        row.put("revision", "3");
        row.put("category_code", "RENTAL");
        row.put("premises_type", "WAREHOUSE");
        row.put("created_by", user.toString());
        row.put("document_no", "RENT-2026-10");
        row.put("title", "测试中心仓10月租金");
        return new com.rigour.analytics.infrastructure.persistence.scope.CostSourceNormalizer(
                        JsonMapper.builder().build())
                .normalize("ERP", row, List.of(), Map.of());
    }

    @Test
    void crossMonthRentalDetailUsesTheSamePeriodAsItsReportAndLedger() {
        var row = crossMonthRent();
        useFacts(List.of(row));
        var original = mock(CostSourceDetail.class);
        when(original.verify("ERP", "RENTAL", row.sourceId()))
                .thenReturn(new CostSourceDetail.State("CONFIRMED", row.revision()));
        var linked =
                new CostDashboardService(
                        store,
                        mock(CostDashboardRefreshService.class),
                        access,
                        original,
                        true,
                        clock);
        var october = query(null, "warehouse", "current", "ALL");
        assertThat(linked.overview(october).current().amount()).isEqualTo("6000.00");
        assertThat(linked.records(october).items())
                .extracting(r -> r.sourceId())
                .containsExactly(row.sourceId());
        assertThat(linked.detail(october, "ERP", row.sourceId()).record().costMonth())
                .isEqualTo("2026-10");
        assertThat(linked.records(september("warehouse")).items()).isEmpty();
        assertThatThrownBy(() -> linked.detail(september("warehouse"), "ERP", row.sourceId()))
                .hasMessageContaining("费用不存在或无访问权限");
        verify(original, times(1)).verify("ERP", "RENTAL", row.sourceId());
    }

    @Test
    void previousPeriodPayrollDetailIncludesTheWholeCostMonthAtAPartialMonthCutoff() {
        var row = salary("E1", false);
        useFacts(List.of(row));
        var original = mock(CostSourceDetail.class);
        when(original.verify("HR", "PAYROLL", row.sourceId()))
                .thenReturn(new CostSourceDetail.State("CONFIRMED", row.revision()));
        var linked =
                new CostDashboardService(
                        store,
                        mock(CostDashboardRefreshService.class),
                        access,
                        original,
                        true,
                        clock);
        var previous = query(null, "labor", "previous", "ALL");
        assertThat(linked.records(previous).items())
                .extracting(r -> r.sourceId())
                .containsExactly(row.sourceId());
        assertThat(linked.detail(previous, "HR", row.sourceId()).record().costMonth())
                .isEqualTo("2026-09");
        assertThatThrownBy(
                        () ->
                                linked.detail(
                                        query(null, "labor", "current", "ALL"),
                                        "HR",
                                        row.sourceId()))
                .hasMessageContaining("费用不存在或无访问权限");
    }

    @Test
    void crossMonthRentalDetailStillChecksCurrentSourceStateAndReadPermission() {
        var row = crossMonthRent();
        useFacts(List.of(row));
        var original = mock(CostSourceDetail.class);
        var linked =
                new CostDashboardService(
                        store,
                        mock(CostDashboardRefreshService.class),
                        access,
                        original,
                        true,
                        clock);
        var october = query(null, "warehouse", "current", "ALL");
        when(original.verify("ERP", "RENTAL", row.sourceId()))
                .thenReturn(new CostSourceDetail.State("VOID", row.revision()));
        assertThatThrownBy(() -> linked.detail(october, "ERP", row.sourceId()))
                .hasMessageContaining("源单据已更新");
        when(original.verify("ERP", "RENTAL", row.sourceId()))
                .thenThrow(new AuthorizationDeniedException("original-record-read"));
        assertThatThrownBy(() -> linked.detail(october, "ERP", row.sourceId()))
                .isInstanceOf(AuthorizationDeniedException.class);
        clearInvocations(original);
        sources.remove("warehouse");
        authorize(null);
        assertThatThrownBy(
                        () ->
                                linked.detail(
                                        query(null, null, "current", "ALL"), "ERP", row.sourceId()))
                .hasMessageContaining("费用不存在或无访问权限");
        verifyNoInteractions(original);
    }

    @Test
    void writesSixCostPreviewFromSameTestedCalculationContract() throws Exception {
        var rows = new ArrayList<>(facts);
        rows.add(salary("E1", false));
        rows.add(salary("E2", false));
        when(store.snapshot(tenant.toString(), "snapshot"))
                .thenReturn(
                        new CostDashboardStore.Snapshot(
                                "snapshot",
                                tenant.toString(),
                                clock.instant(),
                                LocalDate.of(2026, 10, 16),
                                Map.of("BI_COST_NORMALIZATION", "6"),
                                List.of(),
                                rows));
        Map<String, Object> responses = new LinkedHashMap<>();
        for (var scope : List.of("opex", "purchase", "loss")) {
            var cats = new ArrayList<String>();
            cats.add(null);
            CostAnalysis.CATEGORIES.keySet().stream()
                    .filter(k -> scope.equals(Set.of("purchase", "loss").contains(k) ? k : "opex"))
                    .forEach(cats::add);
            for (String category : cats) {
                var dimensions =
                        category == null ? List.of("_") : CostAnalysis.DIMENSIONS.get(category);
                for (String dimension : dimensions) {
                    var q =
                            new CostDashboardQuery(
                                    2026,
                                    9,
                                    "previous",
                                    null,
                                    scope,
                                    category,
                                    dimension.equals("_") ? null : dimension,
                                    null,
                                    "CONFIRMED",
                                    "ALL",
                                    null,
                                    "current",
                                    1,
                                    20,
                                    null,
                                    false);
                    var key = scope + ":" + Objects.toString(category, "_") + ":" + dimension;
                    responses.put(
                            key,
                            Map.of("report", service.overview(q), "records", service.records(q)));
                }
            }
        }
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/cost-dashboard-six-costs-preview.json"),
                JsonMapper.builder().build().writeValueAsString(responses));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "cost.performance",
            matches = "true")
    void benchmark100000FactsWith20ConcurrentReaders() throws Exception {
        var rows = new ArrayList<CostFact>();
        for (int i = 0; i < 100000; i++) {
            var base = facts.get(i % facts.size());
            rows.add(
                    new CostFact(
                            base.source(),
                            "perf-" + i,
                            base.sourceTable(),
                            "PERF-" + i,
                            base.title(),
                            base.category(),
                            base.sourceCategory(),
                            base.city(),
                            base.cityName(),
                            base.rawCity(),
                            base.mappingState(),
                            LocalDate.of(2026, 9, 1 + i % 30),
                            new BigDecimal("1.01"),
                            "CONFIRMED",
                            "UNPAID",
                            base.sourceSystem(),
                            base.employeeCode(),
                            base.employeeName(),
                            base.departmentId(),
                            base.departmentPath(),
                            base.warehouseId(),
                            base.sourceWarehouseId(),
                            base.createdBy(),
                            1L,
                            "",
                            base.dimensions(),
                            List.of()));
        }
        useFacts(rows);
        var q = september(null);
        var expected = service.overview(q).current().amount();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
        var start = new java.util.concurrent.CountDownLatch(1);
        var jobs = new ArrayList<java.util.concurrent.Future<Double>>();
        try {
            for (int i = 0; i < 20; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    long begin = System.nanoTime();
                                    assertThat(service.overview(q).current().amount())
                                            .isEqualTo(expected);
                                    return (System.nanoTime() - begin) / 1_000_000.0;
                                }));
            start.countDown();
            var times = new ArrayList<Double>();
            for (var job : jobs) times.add(job.get(120, java.util.concurrent.TimeUnit.SECONDS));
            times.sort(Double::compare);
            var context = service.context(q);
            long begin = System.nanoTime();
            var csv = CostDashboardExportService.csv(context, service.matching(context));
            double csvMillis = (System.nanoTime() - begin) / 1_000_000.0;
            var result =
                    Map.of(
                            "scope",
                            "local in-memory service only; excludes HTTP, database loading, auth"
                                    + " server and source refresh",
                            "facts",
                            rows.size(),
                            "concurrentReaders",
                            20,
                            "p50Ms",
                            times.get(9),
                            "p95Ms",
                            times.get(18),
                            "maxMs",
                            times.get(19),
                            "csvRows",
                            service.matching(context).size(),
                            "csvBytes",
                            csv.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                            "csvMs",
                            csvMillis);
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target/cost-dashboard-performance.json"),
                    JsonMapper.builder().build().writeValueAsString(result));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void marketScenarioReconcilesMonthlyCostsPurposeMappingAndLinkedExpenseExactly()
            throws Exception {
        var json = JsonMapper.builder().build();
        var fixture =
                json.readTree(
                        getClass().getResourceAsStream("/cost-dashboard/market-scenario.json"));
        var normalizer =
                new com.rigour.analytics.infrastructure.persistence.scope.CostSourceNormalizer(
                        json);
        List<CostFact> scenario = new ArrayList<>();
        Map<String, CostFact> originals = new HashMap<>();
        var cities =
                List.of(
                        new CostDashboardStore.City(
                                "WH", "武汉测试归属", "4201", "10", List.of("1", "10")));
        for (var record : fixture.path("records")) {
            Map<String, String> row = new HashMap<>();
            record.path("fields")
                    .properties()
                    .forEach(e -> row.put(e.getKey(), e.getValue().asText()));
            var fact =
                    normalizer.normalize(
                            record.path("source").asText(),
                            row,
                            cities,
                            Map.of("10", List.of("1", "10")));
            fact =
                    com.rigour.analytics.infrastructure.persistence.scope
                            .CostReimbursementReconciler.reconcile(fact, originals);
            scenario.add(fact);
            if (!"HR".equals(fact.source())) originals.put(fact.key(), fact);
        }
        facts = scenario;
        when(store.state(tenant.toString()))
                .thenReturn(
                        new CostDashboardStore.State(
                                "snapshot",
                                Instant.parse("2026-10-21T06:00:00Z"),
                                Instant.parse("2026-10-21T06:00:00Z"),
                                "SUCCESS"));
        when(store.snapshot(tenant.toString(), "snapshot"))
                .thenReturn(
                        new CostDashboardStore.Snapshot(
                                "snapshot",
                                tenant.toString(),
                                Instant.parse("2026-10-21T06:00:00Z"),
                                LocalDate.of(2026, 10, 21),
                                Map.of("BI_COST_NORMALIZATION", "6"),
                                cities,
                                facts));
        var report = service.overview(query(null, null, "current", "ALL"));
        assertThat(report.current().amount()).isEqualTo("20717.30");
        assertThat(report.purchase()).isEqualTo("7121.85");
        assertThat(report.loss()).isEqualTo("22.63");
        assertThat(report.current().linkedAmount()).isEqualTo("320.00");
        assertThat(report.current().reviewAmount()).isEqualTo("4500.00");
        assertThat(report.current().reviewCount()).isEqualTo(1);
        assertThat(report.monthlyAmount()).isEqualTo("15300.00");
        assertThat(report.cumulative().getLast().current()).isEqualTo("5417.30");
        assertThat(
                        report.categories().stream()
                                .filter(c -> c.key().equals("expense"))
                                .findFirst()
                                .orElseThrow()
                                .current())
                .isEqualTo("1151.00");
        assertThat(
                        report.categories().stream()
                                .filter(c -> c.key().equals("office"))
                                .findFirst()
                                .orElseThrow()
                                .current())
                .isEqualTo("2646.30");
        assertThat(
                        report.payrollComponents().stream()
                                .filter(c -> c.key().equals("commission"))
                                .findFirst()
                                .orElseThrow()
                                .amount())
                .isEqualTo("1000.00");
        var linked =
                scenario.stream()
                        .filter(r -> r.treatment().equals("LINKED"))
                        .findFirst()
                        .orElseThrow();
        assertThat(linked.amount()).isEqualByComparingTo("320.00");
        assertThat(linked.confirmed()).isFalse();
        var ledger = service.records(query(null, null, "current", "ALL"));
        var included = service.matching(service.context(query(null, null, "current", "ALL")));
        assertThat(included).allMatch(CostFact::confirmed);
        assertThat(ledger.summary().count()).isEqualTo(report.current().count());
        assertThat(CostAnalysis.sum(included, r -> true)).isEqualByComparingTo(report.current().amount());
        assertThat(
                        com.rigour.analytics.infrastructure.persistence.scope
                                .CostReimbursementReconciler.reconcile(linked, Map.of())
                                .treatment())
                .isEqualTo("REVIEW");
        Map<String, Object> previews = new LinkedHashMap<>();
        for (String scope : List.of("opex", "purchase", "loss")) {
            List<String> topics = new ArrayList<>();
            topics.add(null);
            topics.addAll(
                    CostAnalysis.CATEGORIES.keySet().stream()
                            .filter(
                                    k ->
                                            scope.equals(
                                                    Set.of("purchase", "loss").contains(k)
                                                            ? k
                                                            : "opex"))
                            .toList());
            for (String topic : topics) {
                List<String> dims = new ArrayList<>();
                dims.add(null);
                if (topic != null) dims.addAll(CostAnalysis.DIMENSIONS.get(topic));
                for (String dim : dims) {
                    var q =
                            new CostDashboardQuery(
                                    2026,
                                    10,
                                    "previous",
                                    null,
                                    scope,
                                    topic,
                                    dim,
                                    null,
                                    "CONFIRMED",
                                    "ALL",
                                    null,
                                    "current",
                                    1,
                                    20,
                                    null,
                                    false);
                    previews.put(
                            scope
                                    + ":"
                                    + Objects.toString(topic, "_")
                                    + ":"
                                    + Objects.toString(dim, "_"),
                            Map.of("report", service.overview(q), "records", service.records(q)));
                }
            }
        }
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/cost-business-contracts.json"),
                json.writeValueAsString(previews));
        var out = java.nio.file.Path.of("target/cost-business-report.json");
        java.nio.file.Files.writeString(
                out, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/cost-business-facts.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(facts));
    }
}
