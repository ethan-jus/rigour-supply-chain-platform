package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.*;
import java.util.*;

class HrPayrollComponentServiceTest {
    final HrPayrollComponentStore catalog = mock(HrPayrollComponentStore.class);
    final HrPayrollStore payroll = mock(HrPayrollStore.class);
    final HrPayrollComponentLifecycleStore lifecycle = mock(HrPayrollComponentLifecycleStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final HrPayrollComponentService service =
            new HrPayrollComponentService(catalog, payroll, source, lifecycle);
    final PayrollRuleResolver resolver = new PayrollRuleResolver(catalog);
    final PayrollSchemeDefinition base = PayrollSchemeTestFixture.definition();
    final PayrollEmployeeView employee =
            new PayrollEmployeeView(
                    "E1", "测试员工", 1L, "销售部", null, LocalDate.of(2026, 1, 1), null, "SALES");
    List<PayrollComponentVersionView> rules;

    @BeforeEach
    void setup() {
        var grades =
                base.baseSalary().tiers().stream()
                        .map(
                                t ->
                                        new PayrollGradeBand(
                                                t.code(),
                                                t.name(),
                                                t.lower(),
                                                t.upper(),
                                                t.lowerInclusive(),
                                                t.upperInclusive()))
                        .toList();
        rules =
                new ArrayList<>(
                        List.of(
                                row(
                                        "salary",
                                        "SALARY",
                                        new PayrollSalaryStandard(
                                                base.baseSalary(),
                                                base.performanceBase(),
                                                base.proration()),
                                        null,
                                        null,
                                        null,
                                        null),
                                row(
                                        "performance",
                                        "PERFORMANCE",
                                        null,
                                        new PayrollPerformanceStandard(
                                                base.performanceMode(),
                                                base.sales(),
                                                base.manager(),
                                                grades),
                                        null,
                                        null,
                                        null),
                                row(
                                        "commission",
                                        "COMMISSION",
                                        null,
                                        null,
                                        base.commission(),
                                        null,
                                        null),
                                row(
                                        "calendar",
                                        "ATTENDANCE",
                                        null,
                                        null,
                                        null,
                                        base.calendar(),
                                        null),
                                row(
                                        "allowance",
                                        "ALLOWANCE",
                                        null,
                                        null,
                                        null,
                                        null,
                                        new PayrollAllowanceStandard(base.allowances(), "NONE"))));
        when(catalog.versions("t")).thenAnswer(invocation -> rules);
    }

    PayrollComponentVersionView row(
            String id,
            String kind,
            PayrollSalaryStandard salary,
            PayrollPerformanceStandard performance,
            PayrollSchemeCommissionPolicy commission,
            PayrollWorkCalendar calendar,
            PayrollAllowanceStandard allowance) {
        boolean personal = kind.equals("SALARY");
        return new PayrollComponentVersionView(
                id,
                id,
                1,
                new PayrollComponentDefinition(
                        id,
                        kind,
                        Set.of("ATTENDANCE", "COMMISSION").contains(kind) ? "ALL" : "SALES",
                        personal ? List.of() : List.of(1L),
                        base.effectiveFrom(),
                        base.effectiveTo(),
                        salary,
                        performance,
                        commission,
                        calendar,
                        allowance,
                        personal ? List.of("E1") : List.of()),
                "测试",
                "actor",
                Instant.EPOCH,
                "PCR20261015000001");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void newRuleCanReplaceAStoppedFamilyButTheRetiredFamilyCannotBeEnabled(int ruleIndex) {
        var actor = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var month = YearMonth.now(ZoneId.of("Asia/Shanghai"));
        var original = rules.get(ruleIndex);
        var stopped =
                new PayrollComponentVersionView(
                        original.id(),
                        original.ruleId(),
                        1,
                        original.definition(),
                        original.reason(),
                        "HR",
                        Instant.EPOCH,
                        original.ruleCode(),
                        1,
                        false,
                        List.of(
                                new PayrollComponentStateChange(
                                        1, month.toString(), false, "停用", "HR", Instant.now())));
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        actor,
                        tenant,
                        actor,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("*:*:*")));
        try {
            when(payroll.departments(eq(tenant.toString()), anyString()))
                    .thenReturn(List.of(new PayrollDepartmentView(1L, null, "销售部", 0)));
            when(source.productCategories(tenant.toString()))
                    .thenReturn(
                            List.of(
                                    new com.rigour.erp.api.v1.model.ErpPayrollCategoryView(
                                            10L, null, "食品", 0),
                                    new com.rigour.erp.api.v1.model.ErpPayrollCategoryView(
                                            20L, null, "周边", 0),
                                    new com.rigour.erp.api.v1.model.ErpPayrollCategoryView(
                                            21L, 20L, "台呢", 0)));
            when(source.dictionary(tenant.toString(), "PRODUCT_UNIT")).thenReturn(Set.of("BOX"));
            var d = original.definition();
            var unrelated =
                    new PayrollComponentVersionView(
                            "unrelated",
                            "unrelated",
                            1,
                            scoped(
                                    d,
                                    "SALARY".equals(d.kind()) ? List.of() : List.of(2L),
                                    "SALARY".equals(d.kind()) ? List.of("E2") : List.of(),
                                    d.role()),
                            "其他范围",
                            "HR",
                            Instant.EPOCH,
                            "PCR-OTHER",
                            1,
                            false,
                            stopped.stateChanges());
            var otherRole =
                    new PayrollComponentVersionView(
                            "other-role",
                            "other-role",
                            1,
                            scoped(d, d.departmentIds(), d.employeeCodes(), "HEADQUARTERS"),
                            "其他身份",
                            "HR",
                            Instant.EPOCH,
                            "PCR-ROLE",
                            1,
                            false,
                            stopped.stateChanges());
            var laterDefinition =
                    new PayrollComponentDefinition(
                            d.name(),
                            d.kind(),
                            d.role(),
                            d.departmentIds(),
                            d.effectiveFrom().plusMonths(1),
                            d.effectiveTo(),
                            d.salary(),
                            d.performance(),
                            d.commission(),
                            d.calendar(),
                            d.allowance(),
                            d.employeeCodes());
            var laterVersion =
                    new PayrollComponentVersionView(
                            "later-version",
                            stopped.ruleId(),
                            2,
                            laterDefinition,
                            "新版本",
                            "HR",
                            Instant.now(),
                            stopped.ruleCode(),
                            1,
                            false,
                            stopped.stateChanges());
            var existing = new ArrayList<>(List.of(stopped, laterVersion, unrelated));
            if (Set.of("PERFORMANCE", "ALLOWANCE").contains(d.kind())) existing.add(otherRole);
            when(catalog.versions(tenant.toString())).thenReturn(existing);
            when(catalog.save(anyString(), any(), anyString()))
                    .thenAnswer(
                            invocation -> {
                                PayrollComponentSaveCommand command = invocation.getArgument(1);
                                return new PayrollComponentVersionView(
                                        "replacement",
                                        "replacement",
                                        1,
                                        command.definition(),
                                        command.reason(),
                                        "HR",
                                        Instant.now(),
                                        "PCR-NEW");
                            });
            var saved =
                    service.save(
                            new PayrollComponentSaveCommand(
                                    "replace-rule", null, original.definition(), "替代停用规则"));
            verify(lifecycle).retire(tenant.toString(), stopped, saved, actor.toString());
            verify(lifecycle).retire(tenant.toString(), laterVersion, saved, actor.toString());
            verify(lifecycle, never())
                    .retire(eq(tenant.toString()), eq(unrelated), any(), anyString());
            verify(lifecycle, never())
                    .retire(eq(tenant.toString()), eq(otherRole), any(), anyString());
            // Historical enabled dates were not a conflict, but an active family still is.
            when(catalog.versions(tenant.toString())).thenReturn(List.of(original));
            assertThatThrownBy(
                            () ->
                                    service.save(
                                            new PayrollComponentSaveCommand(
                                                    "duplicate-calendar",
                                                    null,
                                                    original.definition(),
                                                    "重复配置")))
                    .hasMessageContaining(
                            "ATTENDANCE".equals(original.definition().kind())
                                    ? "部门已配置工作日历"
                                    : "相同适用范围已存在生效规则");
            // A stopped family must not hide another active family with the same scope.
            when(catalog.versions(tenant.toString())).thenReturn(List.of(stopped, saved));
            assertThatThrownBy(
                            () ->
                                    service.save(
                                            new PayrollComponentSaveCommand(
                                                    "active-conflict",
                                                    null,
                                                    original.definition(),
                                                    "不能覆盖使用中规则")))
                    .hasMessageContaining(
                            "ATTENDANCE".equals(original.definition().kind())
                                    ? "部门已配置工作日历"
                                    : "相同适用范围已存在生效规则");
            verify(lifecycle, times(2)).retire(anyString(), any(), any(), anyString());
            var changes = new ArrayList<>(stopped.stateChanges());
            changes.add(
                    new PayrollComponentStateChange(
                            2,
                            original.definition().effectiveFrom().toString().substring(0, 7),
                            false,
                            "新规则替代",
                            "HR",
                            Instant.now(),
                            true,
                            List.of(1L)));
            var retired =
                    new PayrollComponentVersionView(
                            stopped.id(),
                            stopped.ruleId(),
                            1,
                            stopped.definition(),
                            stopped.reason(),
                            "HR",
                            Instant.EPOCH,
                            stopped.ruleCode(),
                            1,
                            false,
                            changes);
            when(catalog.versions(tenant.toString())).thenReturn(List.of(retired));
            assertThatThrownBy(
                            () ->
                                    service.act(
                                            retired.id(),
                                            new PayrollComponentActionCommand(
                                                    "enable-retired", "ENABLE", 1, 2, "尝试恢复")))
                    .hasMessageContaining("规则已废弃");
            assertThatThrownBy(
                            () ->
                                    service.save(
                                            new PayrollComponentSaveCommand(
                                                    "edit-retired",
                                                    retired.id(),
                                                    retired.definition(),
                                                    "修改废弃规则",
                                                    false,
                                                    1)))
                    .hasMessageContaining("规则已废弃");
        } finally {
            TestAuthorizationContext.clear();
        }
    }

    private static PayrollComponentDefinition scoped(
            PayrollComponentDefinition d,
            List<Long> departments,
            List<String> employees,
            String role) {
        return new PayrollComponentDefinition(
                d.name(),
                d.kind(),
                role,
                departments,
                d.effectiveFrom(),
                d.effectiveTo(),
                d.salary(),
                d.performance(),
                d.commission(),
                d.calendar(),
                d.allowance(),
                employees);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void historicalRecalculationUsesReplacementInsteadOfRetiredFamily(int ruleIndex) {
        var original = rules.get(ruleIndex);
        var retired =
                new PayrollComponentVersionView(
                        original.id(),
                        original.ruleId(),
                        1,
                        original.definition(),
                        "旧规则",
                        "HR",
                        Instant.EPOCH,
                        original.ruleCode(),
                        1,
                        false,
                        List.of(
                                new PayrollComponentStateChange(
                                        1, "2026-10", false, "停用", "HR", Instant.now()),
                                new PayrollComponentStateChange(
                                        2,
                                        "2026-09",
                                        false,
                                        "替代",
                                        "HR",
                                        Instant.now(),
                                        true,
                                        List.of(1L))));
        var replacement =
                new PayrollComponentVersionView(
                        "replacement",
                        "replacement",
                        1,
                        original.definition(),
                        "替代",
                        "HR",
                        Instant.now(),
                        "PCR-NEW");
        rules.set(ruleIndex, retired);
        rules.add(replacement);
        var result = resolver.resolve("t", employee, 1L, YearMonth.of(2026, 9));
        assertThat(result.issues()).isEmpty();
        var refs = result.snapshot().definition().ruleReferences();
        assertThat(
                        switch (original.definition().kind()) {
                            case "SALARY" -> refs.salaryId();
                            case "PERFORMANCE" -> refs.performanceId();
                            case "COMMISSION" -> refs.commissionId();
                            case "ATTENDANCE" -> refs.attendanceId();
                            default -> refs.allowanceId();
                        })
                .isEqualTo("replacement");
    }

    @Test
    void explicitlyLinkedAllowanceCannotReuseRetiredDepartmentScope() {
        var original = rules.get(4);
        rules.set(
                4,
                new PayrollComponentVersionView(
                        original.id(),
                        original.ruleId(),
                        1,
                        original.definition(),
                        "旧补贴",
                        "HR",
                        Instant.EPOCH,
                        original.ruleCode(),
                        1,
                        false,
                        List.of(
                                new PayrollComponentStateChange(
                                        1,
                                        "2026-09",
                                        false,
                                        "替代",
                                        "HR",
                                        Instant.now(),
                                        true,
                                        List.of(1L)))));
        var salary = rules.getFirst();
        var d = salary.definition();
        var personal =
                new PayrollPersonalPolicy(
                        List.of(), original.id(), null, null, null, null, null, null);
        var definition =
                new PayrollComponentDefinition(
                        d.name(),
                        d.kind(),
                        d.role(),
                        d.departmentIds(),
                        d.effectiveFrom(),
                        d.effectiveTo(),
                        new PayrollSalaryStandard(
                                d.salary().baseSalary(),
                                d.salary().performanceBase(),
                                d.salary().proration(),
                                personal),
                        null,
                        null,
                        null,
                        null,
                        d.employeeCodes());
        rules.set(
                0,
                new PayrollComponentVersionView(
                        salary.id(),
                        salary.ruleId(),
                        1,
                        definition,
                        "指定补贴",
                        "HR",
                        Instant.EPOCH,
                        salary.ruleCode()));
        var result = resolver.resolve("t", employee, 1L, YearMonth.of(2026, 9));
        assertThat(result.snapshot()).isNull();
        assertThat(result.issues()).contains("个人关联的补贴版本不适用本月、身份或部门，请调整员工薪资标准");
    }

    @Test
    void savesManagerComponentWithFiniteOverlappingRangesAndPartialWeight() {
        var actor = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var range =
                new PayrollMetricStep(
                        new java.math.BigDecimal("1.5"),
                        new java.math.BigDecimal("2"),
                        true,
                        true,
                        "FIXED",
                        new java.math.BigDecimal("1.9"));
        var policy =
                new PayrollManagerPolicy(
                        List.of(
                                new PayrollMetricDefinition(
                                        "store",
                                        "建联门店数",
                                        "MANUAL_CONFIRMED",
                                        null,
                                        new java.math.BigDecimal("30"),
                                        List.of(range, range),
                                        null,
                                        "CONTACTED_STORE_COUNT",
                                        "TIERED")),
                        new PayrollProtectionPolicy("MONTHS", 1, true),
                        null,
                        List.of(),
                        "ACTUAL_COMPLETION");
        var definition =
                new PayrollComponentDefinition(
                        "单项自定义区间",
                        "PERFORMANCE",
                        "CITY_MANAGER",
                        List.of(1L),
                        base.effectiveFrom(),
                        base.effectiveTo(),
                        null,
                        new PayrollPerformanceStandard("MANAGER", null, policy, List.of()),
                        null,
                        null,
                        null,
                        List.of());
        var saved =
                new PayrollComponentVersionView(
                        "new", "new", 1, definition, "配置", "HR", Instant.now(), "PCR-NEW");
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        actor,
                        tenant,
                        actor,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("*:*:*")));
        try {
            when(payroll.departments(eq(tenant.toString()), anyString()))
                    .thenReturn(List.of(new PayrollDepartmentView(1L, null, "销售部", 0)));
            when(catalog.versions(tenant.toString())).thenReturn(List.of());
            when(catalog.save(eq(tenant.toString()), any(), anyString())).thenReturn(saved);
            var command =
                    new PayrollComponentSaveCommand("flexible-metric", null, definition, "配置");
            assertThat(service.save(command)).isEqualTo(saved);
            verify(catalog).save(tenant.toString(), command, actor.toString());
        } finally {
            TestAuthorizationContext.clear();
        }
    }

    @Test
    void calculatesFromEmployeeAmountsAndSeparateRulesWithoutAnyPublishedScheme() {
        var match = resolver.resolve("t", employee, 1L, YearMonth.from(base.effectiveFrom()));
        assertThat(match.issues()).isEmpty();
        assertThat(match.snapshot().definition().baseSalary()).isEqualTo(base.baseSalary());
        assertThat(match.snapshot().definition().performanceBase())
                .isEqualByComparingTo(base.performanceBase());
        assertThat(match.snapshot().definition().allowances()).isEqualTo(base.allowances());
        assertThat(match.snapshot().definition().ruleReferences().allowanceId())
                .isEqualTo("allowance");
    }

    @Test
    void missingEmployeeAmountsDoesNotFallBackToLegacySalaryOrZero() {
        rules.removeIf(r -> r.id().equals("salary"));
        var match = resolver.resolve("t", employee, 1L, YearMonth.from(base.effectiveFrom()));
        assertThat(match.snapshot()).isNull();
        assertThat(match.issues()).contains("员工薪资标准未配置");
    }

    @Test
    void savedParentAndDescendantIdsApplyDepartmentRulesToEverySelectedDepartmentOnly() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        rules.replaceAll(
                rule -> {
                    if ("SALARY".equals(rule.definition().kind())) return rule;
                    tools.jackson.databind.node.ObjectNode node = json.valueToTree(rule);
                    ((tools.jackson.databind.node.ObjectNode) node.get("definition"))
                            .set("departmentIds", json.valueToTree(List.of(1L, 2L, 3L, 4L)));
                    return json.treeToValue(node, PayrollComponentVersionView.class);
                });
        for (long department : List.of(1L, 2L, 3L, 4L)) {
            var match =
                    resolver.resolve(
                            "t", employee, department, YearMonth.from(base.effectiveFrom()));
            assertThat(match.issues()).isEmpty();
            var references = match.snapshot().definition().ruleReferences();
            assertThat(references.performanceId()).isEqualTo("performance");
            assertThat(references.commissionId()).isEqualTo("commission");
            assertThat(references.attendanceId()).isEqualTo("calendar");
            assertThat(references.allowanceId()).isEqualTo("allowance");
        }
        assertThat(
                        resolver.resolve("t", employee, 5L, YearMonth.from(base.effectiveFrom()))
                                .snapshot())
                .isNull();
    }

    @Test
    void conflictingRuleFamiliesAreBlockedInsteadOfTakingTheLatestCreated() {
        rules.add(
                row(
                        "performance-2",
                        "PERFORMANCE",
                        null,
                        rules.get(1).definition().performance(),
                        null,
                        null,
                        null));
        var match = resolver.resolve("t", employee, 1L, YearMonth.from(base.effectiveFrom()));
        assertThat(match.snapshot()).isNull();
        assertThat(match.issues()).contains("绩效规则存在多条冲突配置");
    }

    @Test
    void missingCalendarIsNotReplacedWithDefaultDates() {
        rules.removeIf(r -> r.id().equals("calendar"));
        assertThat(
                        resolver.resolve("t", employee, 1L, YearMonth.from(base.effectiveFrom()))
                                .issues())
                .contains("考勤规则未配置");
    }

    @Test
    void employeeAmountsNeverApplyToAnotherEmployee() {
        var other =
                new PayrollEmployeeView(
                        "E2", "其他员工", 1L, "销售部", null, employee.entryDate(), null, "SALES");
        assertThat(resolver.resolve("t", other, 1L, YearMonth.from(base.effectiveFrom())).issues())
                .contains("员工薪资标准未配置");
    }

    @Test
    void explicitCutoverDoesNotAffectOlderMonthsWhenNoNewRuleApplies() {
        rules.clear();
        when(catalog.unifiedFrom("t")).thenReturn("2026-10");
        assertThat(service.unified("t", YearMonth.of(2026, 9))).isFalse();
        assertThat(service.unified("t", YearMonth.of(2026, 10))).isTrue();
    }

    @Test
    void retryOfAlreadySavedRuleReturnsItBeforeOverlapValidation() {
        var actor = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var row = rules.get(1);
        com.rigour.shared.context.TestAuthorizationContext.set(
                new com.rigour.shared.context.CallerIdentity(
                        "SERVICE",
                        actor,
                        tenant,
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("*:*:*")));
        try {
            when(payroll.departments(tenant.toString(), "hr:payroll-rule:write"))
                    .thenReturn(List.of(new PayrollDepartmentView(1L, null, "销售部", 1)));
            when(catalog.byRequest(tenant.toString(), "request")).thenReturn(Optional.of(row));
            assertThat(
                            service.save(
                                    new PayrollComponentSaveCommand(
                                            "request", null, row.definition(), row.reason())))
                    .isEqualTo(row);
            verify(catalog, never()).save(anyString(), any(), anyString());
        } finally {
            com.rigour.shared.context.TestAuthorizationContext.clear();
        }
    }

    @Test
    void futureVersionsDoNotChangeCurrentMonthSnapshot() {
        var initial = resolver.resolve("t", employee, 1L, YearMonth.from(base.effectiveFrom()));
        var original = rules.getFirst();
        var d = original.definition();
        rules.add(
                new PayrollComponentVersionView(
                        "next",
                        original.ruleId(),
                        2,
                        new PayrollComponentDefinition(
                                d.name(),
                                d.kind(),
                                d.role(),
                                d.departmentIds(),
                                d.effectiveFrom().plusMonths(1),
                                d.effectiveTo(),
                                d.salary(),
                                null,
                                null,
                                null,
                                null,
                                d.employeeCodes()),
                        "调薪",
                        "actor",
                        Instant.now(),
                        original.ruleCode()));
        assertThat(
                        resolver.resolve("t", employee, 1L, YearMonth.from(base.effectiveFrom()))
                                .snapshot())
                .isEqualTo(initial.snapshot());
    }

    @Test
    void expiredRevisionNeverReactivatesAnOlderOpenEndedVersion() {
        var original = rules.getFirst();
        var d = original.definition();
        rules.add(
                new PayrollComponentVersionView(
                        "ending",
                        original.ruleId(),
                        2,
                        new PayrollComponentDefinition(
                                d.name(),
                                d.kind(),
                                d.role(),
                                d.departmentIds(),
                                d.effectiveFrom().plusMonths(1),
                                d.effectiveFrom()
                                        .plusMonths(1)
                                        .withDayOfMonth(
                                                d.effectiveFrom().plusMonths(1).lengthOfMonth()),
                                d.salary(),
                                null,
                                null,
                                null,
                                null,
                                d.employeeCodes()),
                        "到期",
                        "actor",
                        Instant.now(),
                        original.ruleCode()));
        var result =
                resolver.resolve(
                        "t", employee, 1L, YearMonth.from(d.effectiveFrom()).plusMonths(2));
        assertThat(result.snapshot()).isNull();
        assertThat(result.issues()).contains("员工薪资标准未配置");
    }

    @Test
    void employeeDirectoryPermissionDoesNotGrantSalaryReadOrWrite() {
        com.rigour.shared.context.TestAuthorizationContext.set(
                new com.rigour.shared.context.CallerIdentity(
                        "SERVICE",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:employee:read", "hr:employee:update")));
        try {
            assertThatThrownBy(() -> service.list("SALARY", "E1"))
                    .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
            assertThatThrownBy(
                            () ->
                                    service.save(
                                            new PayrollComponentSaveCommand(
                                                    "denied",
                                                    null,
                                                    rules.getFirst().definition(),
                                                    "测试权限")))
                    .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
            verify(catalog, never()).save(anyString(), any(), anyString());
        } finally {
            com.rigour.shared.context.TestAuthorizationContext.clear();
        }
    }

    @Test
    void fixedNonSalesSalaryDoesNotRequireASyntheticZeroAllowanceRule() {
        rules.removeIf(r -> r.definition().kind().equals("ALLOWANCE"));
        rules.replaceAll(
                r -> {
                    var d = r.definition();
                    var salary =
                            d.salary() == null
                                    ? null
                                    : new PayrollSalaryStandard(
                                            new PayrollSchemeBaseSalary(
                                                    "FIXED",
                                                    new java.math.BigDecimal("6000"),
                                                    List.of(),
                                                    null),
                                            new java.math.BigDecimal("2000"),
                                            base.proration());
                    var performance =
                            d.performance() == null
                                    ? null
                                    : new PayrollPerformanceStandard(
                                            "FIXED", null, null, List.of());
                    return new PayrollComponentVersionView(
                            r.id(),
                            r.ruleId(),
                            r.version(),
                            new PayrollComponentDefinition(
                                    d.name(),
                                    d.kind(),
                                    "ALL".equals(d.role()) ? "ALL" : "HEADQUARTERS",
                                    d.departmentIds(),
                                    d.effectiveFrom(),
                                    d.effectiveTo(),
                                    salary,
                                    performance,
                                    d.commission(),
                                    d.calendar(),
                                    null,
                                    d.employeeCodes()),
                            r.reason(),
                            r.createdBy(),
                            r.createdTime(),
                            r.ruleCode());
                });
        var result = resolver.resolve("t", employee, 1L, YearMonth.from(base.effectiveFrom()));
        assertThat(result.issues()).isEmpty();
        assertThat(result.snapshot().definition().allowances()).isEmpty();
        assertThat(result.snapshot().definition().ruleReferences().allowanceId()).isNull();
    }

    @Test
    void stoppingARuleAffectsItsOperationMonthWithoutChangingHistoricalMonths() {
        var original = rules.get(2);
        var stopped =
                new PayrollComponentVersionView(
                        original.id(),
                        original.ruleId(),
                        1,
                        original.definition(),
                        original.reason(),
                        original.createdBy(),
                        original.createdTime(),
                        original.ruleCode(),
                        1,
                        false,
                        List.of(
                                new PayrollComponentStateChange(
                                        1, "2026-10", false, "停用", "HR", Instant.now())));
        rules.set(2, stopped);
        assertThat(resolver.resolve("t", employee, 1L, YearMonth.of(2026, 9)).issues()).isEmpty();
        assertThat(resolver.resolve("t", employee, 1L, YearMonth.of(2026, 10)).issues())
                .contains("提成规则已停用，请启用或配置替代规则");
        var changes = new ArrayList<>(stopped.stateChanges());
        changes.add(
                new PayrollComponentStateChange(2, "2026-10", true, "重新启用", "HR", Instant.now()));
        rules.set(
                2,
                new PayrollComponentVersionView(
                        original.id(),
                        original.ruleId(),
                        1,
                        original.definition(),
                        original.reason(),
                        original.createdBy(),
                        original.createdTime(),
                        original.ruleCode(),
                        1,
                        false,
                        changes));
        assertThat(resolver.resolve("t", employee, 1L, YearMonth.of(2026, 10)).issues()).isEmpty();
    }

    @Test
    void stoppedRulesCanBeRevisedAtHistoricalMonthsWithNewDepartmentScopeButActiveRulesCannot() {
        var actor = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var month = YearMonth.now(ZoneId.of("Asia/Shanghai"));
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var original = rules.get(3);
        var stopped =
                new PayrollComponentVersionView(
                        original.id(),
                        original.ruleId(),
                        1,
                        original.definition(),
                        original.reason(),
                        original.createdBy(),
                        original.createdTime(),
                        original.ruleCode(),
                        3,
                        false,
                        List.of(
                                new PayrollComponentStateChange(
                                        1, month.toString(), false, "停用", "HR", Instant.EPOCH)));
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        actor,
                        tenant,
                        actor,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("*:*:*")));
        try {
            when(payroll.departments(eq(tenant.toString()), anyString()))
                    .thenReturn(
                            List.of(
                                    new PayrollDepartmentView(1L, null, "销售部", 0),
                                    new PayrollDepartmentView(2L, 1L, "北京", 0)));
            when(catalog.versions(tenant.toString())).thenReturn(List.of(stopped));
            when(catalog.save(anyString(), any(), anyString()))
                    .thenAnswer(
                            invocation -> {
                                PayrollComponentSaveCommand command = invocation.getArgument(1);
                                return new PayrollComponentVersionView(
                                        "new",
                                        stopped.ruleId(),
                                        2,
                                        command.definition(),
                                        command.reason(),
                                        "HR",
                                        Instant.now(),
                                        stopped.ruleCode(),
                                        1,
                                        false,
                                        stopped.stateChanges());
                            });
            tools.jackson.databind.node.ObjectNode node = json.valueToTree(original.definition());
            node.put("effectiveFrom", month.atDay(1).toString());
            node.set("departmentIds", json.valueToTree(List.of(1L, 2L)));
            var definition = json.treeToValue(node, PayrollComponentDefinition.class);
            var saved =
                    service.save(
                            new PayrollComponentSaveCommand(
                                    "stopped-new", stopped.id(), definition, "修正部门范围", false, 3));
            assertThat(saved.version()).isEqualTo(2);
            assertThat(saved.enabledAt(month.atEndOfMonth())).isFalse();
            assertThat(saved.definition().departmentIds()).containsExactly(1L, 2L);
            assertThat(stopped.definition()).isEqualTo(original.definition());
            assertThatThrownBy(
                            () ->
                                    service.save(
                                            new PayrollComponentSaveCommand(
                                                    "stale",
                                                    stopped.id(),
                                                    definition,
                                                    "过期版本",
                                                    false,
                                                    2)))
                    .hasMessageContaining("已变更");
            node.put("effectiveFrom", month.minusMonths(1).atDay(1).toString());
            var historical = json.treeToValue(node, PayrollComponentDefinition.class);
            var backdated =
                    service.save(
                            new PayrollComponentSaveCommand(
                                    "past", stopped.id(), historical, "修正原月份配置", false, 3));
            assertThat(backdated.definition().effectiveFrom())
                    .isEqualTo(month.minusMonths(1).atDay(1));
            assertThat(backdated.enabledAt(month.atEndOfMonth())).isFalse();
            node.put(
                    "effectiveFrom",
                    original.definition().effectiveFrom().minusMonths(1).toString());
            var beforeOriginal = json.treeToValue(node, PayrollComponentDefinition.class);
            assertThat(
                            service.save(
                                            new PayrollComponentSaveCommand(
                                                    "earlier",
                                                    stopped.id(),
                                                    beforeOriginal,
                                                    "修正原开始月份",
                                                    false,
                                                    3))
                                    .definition()
                                    .effectiveFrom())
                    .isEqualTo(beforeOriginal.effectiveFrom());
        } finally {
            TestAuthorizationContext.clear();
        }
    }

    @Test
    void deletedUnstartedVersionDoesNotReplaceTheCurrentSalary() {
        var current = rules.getFirst();
        var d = current.definition();
        rules.add(
                new PayrollComponentVersionView(
                        "deleted-next",
                        current.ruleId(),
                        2,
                        new PayrollComponentDefinition(
                                d.name(),
                                d.kind(),
                                d.role(),
                                d.departmentIds(),
                                LocalDate.of(2026, 11, 1),
                                d.effectiveTo(),
                                d.salary(),
                                null,
                                null,
                                null,
                                null,
                                d.employeeCodes()),
                        "删除",
                        "HR",
                        Instant.now(),
                        current.ruleCode(),
                        2,
                        true,
                        List.of()));
        var result = resolver.resolve("t", employee, 1L, YearMonth.of(2026, 11));
        assertThat(result.issues()).isEmpty();
        assertThat(result.snapshot().definition().ruleReferences().salaryId())
                .isEqualTo(current.id());
    }

    @Test
    void deletingAnEffectiveRuleOrStoppingAFutureRuleIsRejectedBeforePersistence() {
        var tenant = UUID.randomUUID();
        com.rigour.shared.context.TestAuthorizationContext.set(
                new com.rigour.shared.context.CallerIdentity(
                        "SERVICE",
                        UUID.randomUUID(),
                        tenant,
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("*:*:*")));
        try {
            when(catalog.versions(tenant.toString())).thenAnswer(i -> rules);
            when(payroll.departments(eq(tenant.toString()), anyString()))
                    .thenReturn(List.of(new PayrollDepartmentView(1L, null, "销售部", 1)));
            var original = rules.get(4);
            var d = original.definition();
            var currentMonth = YearMonth.now(ZoneId.of("Asia/Shanghai"));
            var current =
                    new PayrollComponentVersionView(
                            original.id(),
                            original.ruleId(),
                            1,
                            new PayrollComponentDefinition(
                                    d.name(),
                                    d.kind(),
                                    d.role(),
                                    d.departmentIds(),
                                    currentMonth.atDay(1),
                                    d.effectiveTo(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    d.allowance(),
                                    List.of()),
                            "创建",
                            "HR",
                            Instant.EPOCH);
            rules.set(4, current);
            assertThatThrownBy(
                            () ->
                                    service.act(
                                            current.id(),
                                            new PayrollComponentActionCommand(
                                                    "delete-current", "DELETE", 1, 0, "测试")))
                    .hasMessageContaining("尚未生效");
            var future =
                    new PayrollComponentVersionView(
                            original.id(),
                            original.ruleId(),
                            1,
                            new PayrollComponentDefinition(
                                    d.name(),
                                    d.kind(),
                                    d.role(),
                                    d.departmentIds(),
                                    currentMonth.plusMonths(1).atDay(1),
                                    d.effectiveTo(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    d.allowance(),
                                    List.of()),
                            "创建",
                            "HR",
                            Instant.EPOCH);
            rules.set(4, future);
            assertThatThrownBy(
                            () ->
                                    service.act(
                                            future.id(),
                                            new PayrollComponentActionCommand(
                                                    "stop-future", "DISABLE", 1, 0, "测试")))
                    .hasMessageContaining("当前有效期间");
            when(lifecycle.inUse(tenant.toString(), future.id())).thenReturn(true);
            assertThatThrownBy(
                            () ->
                                    service.act(
                                            future.id(),
                                            new PayrollComponentActionCommand(
                                                    "delete-used", "DELETE", 1, 0, "测试")))
                    .hasMessageContaining("确认结果引用");
            verify(lifecycle, never()).act(anyString(), any(), any(), anyString(), any());
        } finally {
            com.rigour.shared.context.TestAuthorizationContext.clear();
        }
    }
}
