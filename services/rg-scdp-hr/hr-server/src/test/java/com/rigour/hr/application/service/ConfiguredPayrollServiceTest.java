package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class ConfiguredPayrollServiceTest {
    HrPayrollStore payroll = mock(HrPayrollStore.class);
    HrPayrollSchemeStore schemes = mock(HrPayrollSchemeStore.class);
    HrPayrollSource source = mock(HrPayrollSource.class);
    ConfiguredCommissionService commissions = mock(ConfiguredCommissionService.class);
    PayrollEmployeeView employee =
            new PayrollEmployeeView(
                    "A", "员工", 1L, "销售部", null, LocalDate.of(2026, 9, 15), null, "SALES");
    ConfiguredPayrollService service;
    PayrollRuleResolver rules = mock(PayrollRuleResolver.class);

    @BeforeEach
    void setup() {
        when(schemes.versions("t"))
                .thenReturn(
                        List.of(
                                PayrollSchemeTestFixture.version(
                                        PayrollSchemeTestFixture.definition())));
        when(schemes.facts(anyString(), anyString(), anyString()))
                .thenReturn(new PayrollSchemeFactsView(0, null, List.of(), ""));
        when(payroll.input(anyString(), anyString(), anyString()))
                .thenReturn(new PayrollInputCommand(0, null, null, BigDecimal.ZERO, ""));
        when(payroll.departmentAt(anyString(), anyString(), any())).thenReturn(1L);
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenReturn(
                        new ConfiguredCommissionResult(
                                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of()));
        service =
                new ConfiguredPayrollService(
                        payroll,
                        schemes,
                        source,
                        mock(HrAttendanceStore.class),
                        commissions,
                        JsonMapper.builder().build());
        service.setRules(rules);
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(
                        new PayrollRuleMatch(
                                PayrollSchemeTestFixture.version(
                                        PayrollSchemeTestFixture.definition()),
                                List.of()));
    }

    @Test
    void annualReferenceVersionIsFrozenAndChangingItInvalidatesTheConfirmationFingerprint() {
        var definition =
                PayrollSchemeTestFixture.definition()
                        .withCalendar(HrStatutoryCalendarResolverTest.automatic(), null);
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(
                        new PayrollRuleMatch(
                                PayrollSchemeTestFixture.version(definition), List.of()));
        when(source.statutoryCalendar("t", 2026))
                .thenReturn(HrStatutoryCalendarResolverTest.reference(2026, "v1"));
        var before =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(before.scheme().scheme().definition().calendar().referenceVersion())
                .isEqualTo("v1");
        when(source.statutoryCalendar("t", 2026))
                .thenReturn(HrStatutoryCalendarResolverTest.reference(2026, "v2"));
        var after =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(after.sourceFingerprint()).isNotEqualTo(before.sourceFingerprint());
        assertThat(before.scheme().scheme().definition().calendar().referenceVersion())
                .isEqualTo("v1");
    }

    @Test
    void firstCalendarMonthUsesBronzeBaseAndBothExemptions() {
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.baseSalary()).isEqualByComparingTo("3000");
        assertThat(result.performance()).isEqualByComparingTo("1000");
        assertThat(result.allowance()).isEqualByComparingTo("750");
        assertThat(result.grossPay()).isEqualByComparingTo("4750");
        assertThat(result.scheme().salaryTier()).isEqualTo("BRONZE");
        verify(source, never()).performanceEvidence(anyString(), anyString(), any(), any());
    }

    @Test
    void unavailableProductSourceDoesNotBecomeZeroCommissionOrConfirmableSalary() {
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("product source offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.commission()).isNull();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("商品来源暂不可用"));
    }

    @Test
    void confirmationFingerprintChangesWhenMatchedCommissionChanges() {
        var before =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenReturn(
                        new ConfiguredCommissionResult(
                                BigDecimal.TEN, BigDecimal.ZERO, List.of(), List.of()));
        var after =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(after.sourceFingerprint()).isNotEqualTo(before.sourceFingerprint());
    }

    @Test
    void missingIndependentRulesNeverFallBackToOldSchemeOrOldMonth() {
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(new PayrollRuleMatch(null, List.of("员工薪资标准未配置")));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).containsExactly("员工薪资标准未配置");
        verify(source, never()).payrollLines(anyString(), any(), any(), any());
    }

    @Test
    void unavailableOrdersDoNotBecomeZeroAchievement() {
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("offline"));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.scheme().achievement()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("来源暂不可用"));
    }

    @Test
    void fixedHeadquartersSalaryWithoutPerformanceOrCommissionNeedsNoOrderSource() {
        useIndependentHeadquartersPolicy("NONE", List.of());
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("commission product source offline"));
        var result = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.baseSalary()).isEqualByComparingTo("6000");
        assertThat(result.performance()).isZero();
        assertThat(result.commission()).isZero();
        assertThat(result.grossPay()).isEqualByComparingTo("6000");
        assertThat(result.netPay()).isEqualByComparingTo("6000");
        assertThat(result.scheme().achievement()).isNull();
        verify(source, never()).payrollLines(anyString(), any(), any(), any());
        verifyNoInteractions(commissions);
    }

    @Test
    void fixedHeadquartersPerformanceUsesPersonalCompletionWithoutOrderSource() {
        useIndependentHeadquartersPolicy("FIXED", List.of());
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        when(schemes.facts(anyString(), anyString(), anyString()))
                .thenReturn(new PayrollSchemeFactsView(1, null, List.of(), "月度核定", new BigDecimal("80")));
        var result = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.performance()).isEqualByComparingTo("800");
        assertThat(result.grossPay()).isEqualByComparingTo("6800");
        verify(source, never()).payrollLines(anyString(), any(), any(), any());
    }

    @Test
    void headquartersWithCommissionRulesStillRequiresAvailableOrders() {
        useIndependentHeadquartersPolicy("NONE", PayrollSchemeTestFixture.definition().commission().rules());
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        var result = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.commission()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("订单付清核算来源暂不可用"));
        verify(source).payrollLines(eq("t"), eq(employee.employeeCode()), isNull(), any());
    }

    @Test
    void headquartersWithHistoricalSalesOrCommissionStillRequiresOrders() {
        useIndependentHeadquartersPolicy("NONE", List.of());
        when(rules.hasHistoricalOrderDependency(eq("t"), eq(employee), eq(Set.of(1L)), any()))
                .thenReturn(true);
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        var result = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("订单付清核算来源暂不可用"));
    }

    @Test
    void headquartersWithLegacySalesSchemeStillRequiresOrders() {
        useIndependentHeadquartersPolicy("NONE", List.of());
        when(schemes.versions("t")).thenReturn(List.of(
                PayrollSchemeTestFixture.version(PayrollSchemeTestFixture.definition())));
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        var result = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("订单付清核算来源暂不可用"));
    }

    @Test
    void headquartersWithMissingAssignmentHistoryDoesNotAssumeNoHistoricalCommission() {
        useIndependentHeadquartersPolicy("NONE", List.of());
        when(payroll.departmentsDuring(anyString(), anyString(), any(), any())).thenReturn(Set.of());
        when(source.payrollLines(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("order source offline"));
        var result = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.grossPay()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("订单付清核算来源暂不可用"));
    }

    private void useIndependentHeadquartersPolicy(
            String mode, List<PayrollSchemeCommissionRule> commissionRules) {
        employee = new PayrollEmployeeView("A", "总部员工", 1L, "总部", null,
                LocalDate.of(2026, 9, 15), null, "HEADQUARTERS");
        var d = PayrollSchemeTestFixture.definition();
        var definition = new PayrollSchemeDefinition(
                d.name(), "HEADQUARTERS", d.departmentIds(), d.employeeCodes(), d.effectiveFrom(), d.effectiveTo(),
                new PayrollSchemeBaseSalary("FIXED", new BigDecimal("6000"), List.of(), null),
                new BigDecimal("1000"), mode, null, null, d.calendar(), d.proration(), List.of(),
                new PayrollSchemeCommissionPolicy("ORDER_DATE", "ORDER_DATE", commissionRules), d.remark());
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(new PayrollRuleMatch(PayrollSchemeTestFixture.version(definition), List.of()));
        when(schemes.versions("t")).thenReturn(List.of(PayrollSchemeTestFixture.version(definition)));
        when(payroll.departmentsDuring(anyString(), anyString(), any(), any())).thenReturn(Set.of(1L));
    }

    @Test
    void silverGradeSelectsBothSalaryAndPerformanceAndCapsCollectionAtOne() {
        employee =
                new PayrollEmployeeView(
                        "A", "员工", 1L, "销售部", null, LocalDate.of(2026, 8, 1), null, "SALES");
        var line =
                new com.rigour.order.api.v1.model.OrderPayrollLineView(
                        "L",
                        "O",
                        "SO",
                        "A",
                        "C",
                        1L,
                        1L,
                        1L,
                        "方便面",
                        "BOX",
                        LocalDate.of(2026, 9, 1),
                        BigDecimal.ONE,
                        new BigDecimal("11000"),
                        new BigDecimal("11000"),
                        new BigDecimal("11000"),
                        new BigDecimal("11000"),
                        LocalDate.of(2026, 9, 2),
                        BigDecimal.ZERO,
                        null,
                        "COMPLETE");
        when(source.payrollLines(eq("t"), eq("A"), isNull(), any())).thenReturn(List.of(line));
        when(source.performanceEvidence(anyString(), anyString(), any(), any()))
                .thenReturn(
                        new com.rigour.sales.api.v1.model.SalesPayrollEvidenceView(
                                true, List.of()));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(result.baseSalary()).isEqualByComparingTo("4000");
        assertThat(result.scheme().performanceBase()).isEqualByComparingTo("1500");
        assertThat(result.performanceDetail().processPay()).isZero();
        assertThat(result.performanceDetail().collectionPay()).isEqualByComparingTo("900");
        assertThat(result.grossPay()).isEqualByComparingTo("5650");
    }

    @Test
    void incompleteOrderAttributionMustNotSelectBronzeAsZeroAchievement() {
        var line =
                new com.rigour.order.api.v1.model.OrderPayrollLineView(
                        "L",
                        "O",
                        "SO",
                        "A",
                        "C",
                        null,
                        1L,
                        1L,
                        "商品",
                        "BOX",
                        LocalDate.of(2026, 9, 1),
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        LocalDate.of(2026, 9, 2),
                        BigDecimal.ZERO,
                        null,
                        "ATTRIBUTION_PENDING");
        var issues = new ArrayList<String>();
        assertThat(
                        ConfiguredPayrollService.achievement(
                                List.of(line),
                                LocalDate.of(2026, 9, 1),
                                LocalDate.of(2026, 9, 30),
                                issues))
                .isNull();
        assertThat(issues).isNotEmpty();
    }

    @Test
    void manualAdjustmentAndHistoricalCorrectionRemainDistinctAndChangeFingerprint() {
        when(payroll.input(anyString(), anyString(), anyString()))
                .thenReturn(
                        new PayrollInputCommand(
                                1,
                                null,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "测试核定",
                                new BigDecimal("375")));
        when(payroll.corrections(anyString(), anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new PayrollCorrectionView(
                                        "C",
                                        "P0",
                                        "2026-08",
                                        "2026-09",
                                        new BigDecimal("-200"),
                                        "原期退款冲回",
                                        "ACTIVE",
                                        1,
                                        Instant.EPOCH)));
        var before =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(before.performance()).isZero();
        assertThat(before.allowance()).isEqualByComparingTo("375");
        assertThat(before.correctionAmount()).isEqualByComparingTo("-200");
        assertThat(before.netPay()).isEqualByComparingTo("3175");
        when(payroll.corrections(anyString(), anyString(), anyString())).thenReturn(List.of());
        var after =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(after.sourceFingerprint()).isNotEqualTo(before.sourceFingerprint());
    }

    private void usePolicy(
            String role, String mode, PayrollSalesPolicy sales, PayrollPersonalPolicy personal) {
        var d = PayrollSchemeTestFixture.definition();
        var next =
                new PayrollSchemeDefinition(
                        d.name(),
                        role,
                        d.departmentIds(),
                        d.employeeCodes(),
                        d.effectiveFrom(),
                        d.effectiveTo(),
                        "HEADQUARTERS".equals(role)
                                ? new PayrollSchemeBaseSalary(
                                        "FIXED", new BigDecimal("8000"), List.of(), null)
                                : d.baseSalary(),
                        d.performanceBase(),
                        mode,
                        sales,
                        d.manager(),
                        d.calendar(),
                        d.proration(),
                        "HEADQUARTERS".equals(role) ? List.of() : d.allowances(),
                        d.commission(),
                        d.remark(),
                        d.ruleReferences(),
                        personal);
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(
                        new PayrollRuleMatch(PayrollSchemeTestFixture.version(next), List.of()));
    }

    @Test
    void headquartersRequiresMonthlyCompletionAndCapturesActualPercentage() {
        usePolicy("HEADQUARTERS", "FIXED", PayrollSchemeTestFixture.definition().sales(), null);
        var pending =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(pending.issues()).anyMatch(v -> v.contains("绩效完成率"));
        when(schemes.facts(anyString(), anyString(), anyString()))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                1,
                                new BigDecimal("4000"),
                                List.of(),
                                "月度核定",
                                new BigDecimal("80"),
                                new BigDecimal("100")));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.performance()).isEqualByComparingTo("3200");
        assertThat(result.commission()).isEqualByComparingTo("100");
        assertThat(result.scheme().performanceCompletionPercent()).isEqualByComparingTo("80");
        assertThat(result.sourceFingerprint()).isNotEqualTo(pending.sourceFingerprint());
    }

    @Test
    void headquartersEmployeesInTheSameDepartmentUseTheirOwnMonthlyCompletion() {
        usePolicy("HEADQUARTERS", "FIXED", PayrollSchemeTestFixture.definition().sales(), null);
        when(schemes.facts("t", "A", "2026-09"))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                1,
                                new BigDecimal("4000"),
                                List.of(),
                                "A个人月度核定",
                                new BigDecimal("80")));
        when(schemes.facts("t", "B", "2026-09"))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                1,
                                new BigDecimal("4000"),
                                List.of(),
                                "B个人月度核定",
                                new BigDecimal("110")));
        var colleague =
                new PayrollEmployeeView(
                        "B",
                        "同部门员工B",
                        employee.departmentId(),
                        employee.departmentName(),
                        null,
                        employee.entryDate(),
                        null,
                        "HEADQUARTERS");
        var a = service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        var b = service.calculateIfConfigured("t", colleague, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(a.performance()).isEqualByComparingTo("3200");
        assertThat(b.performance()).isEqualByComparingTo("4400");
        assertThat(b.scheme().performanceCompletionPercent()).isEqualByComparingTo("110");
        var nextMonth =
                service.calculateIfConfigured("t", colleague, YearMonth.of(2026, 10)).orElseThrow();
        assertThat(nextMonth.performance()).isNull();
        assertThat(nextMonth.issues()).anyMatch(v -> v.contains("绩效完成率"));
    }

    @Test
    void firstMonthOnlyExemptsCollectionAndFirstTenDaysOfProcessPlusPersonalExceptions() {
        var old = PayrollSchemeTestFixture.definition().sales();
        var sales =
                new PayrollSalesPolicy(
                        old.processPercent(),
                        7,
                        old.checkinPenalty(),
                        3,
                        old.minimumSeconds(),
                        old.visitPenalty(),
                        true,
                        old.targets(),
                        old.resultCap(),
                        old.checkinCountMode(),
                        10);
        usePolicy(
                "SALES",
                "SALES",
                sales,
                new PayrollPersonalPolicy(
                        List.of(LocalDate.of(2026, 9, 25)),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        when(source.performanceEvidence(anyString(), anyString(), any(), any()))
                .thenReturn(
                        new com.rigour.sales.api.v1.model.SalesPayrollEvidenceView(
                                true, List.of()));
        var result =
                service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(result.performanceDetail().collectionPay()).isEqualByComparingTo("600");
        assertThat(result.performanceDetail().days())
                .allMatch(d -> d.date().isAfter(LocalDate.of(2026, 9, 25)));
        assertThat(result.performanceDetail().days()).isNotEmpty();
        assertThat(result.performanceDetail().processDeduction()).isPositive();
        assertThat(result.scheme().scheme().definition().sales().dailyCheckins()).isEqualTo(7);
        assertThat(result.scheme().scheme().definition().sales().dailyVisits()).isEqualTo(3);
    }
}
