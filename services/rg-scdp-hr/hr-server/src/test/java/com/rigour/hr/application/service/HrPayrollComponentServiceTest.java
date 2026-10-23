package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

class HrPayrollComponentServiceTest {
    final HrPayrollComponentStore catalog = mock(HrPayrollComponentStore.class);
    final HrPayrollStore payroll = mock(HrPayrollStore.class);
    final HrPayrollComponentLifecycleStore lifecycle = mock(HrPayrollComponentLifecycleStore.class);
    final HrPayrollComponentService service =
            new HrPayrollComponentService(catalog, payroll, mock(HrPayrollSource.class), lifecycle);
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
