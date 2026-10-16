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
}
