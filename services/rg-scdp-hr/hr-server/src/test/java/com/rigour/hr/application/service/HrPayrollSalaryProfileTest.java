package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class HrPayrollSalaryProfileTest {
    final HrPayrollStore store = mock(HrPayrollStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final HrPayrollService service =
            new HrPayrollService(store, source, JsonMapper.builder().build());

    PayrollEmployeeView employee(String category) {
        return new PayrollEmployeeView(
                "E1", "测试员工", 10L, "测试销售部", "测试城市", LocalDate.of(2025, 1, 1), null, category);
    }

    PayrollRuleCommand rule(
            String kind,
            String name,
            String amount,
            PayrollPerformancePolicy performance,
            String type,
            PayrollFixedAllowance fixed) {
        return new PayrollRuleCommand(
                UUID.randomUUID().toString(),
                null,
                name,
                kind,
                "BASE".equals(kind) ? "E1" : null,
                List.of(10L),
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal(amount),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                true,
                "",
                performance,
                type,
                fixed);
    }

    PayrollRuleView view(String id, PayrollRuleCommand rule) {
        return new PayrollRuleView(id, 1, rule, Instant.EPOCH);
    }

    @BeforeEach
    void setup() {
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:payroll:calculate", "hr:payroll-rule:write")));
        when(store.employee(anyString(), eq("E1"), anyString())).thenReturn(employee("SALES"));
        when(store.salarySource(anyString(), eq("E1")))
                .thenReturn(
                        new PayrollSalarySourceView(
                                "SALES", false, null, "5000", "3000", LocalDate.of(2026, 1, 1)));
        when(store.input(anyString(), eq("E1"), anyString()))
                .thenReturn(new PayrollInputCommand(0, null, null, BigDecimal.ZERO, ""));
        when(store.departmentAt(anyString(), anyString(), any())).thenReturn(10L);
        when(store.saveCalculation(
                        anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenAnswer(
                        i ->
                                new PayrollDetailView(
                                        "P1",
                                        "2026-09",
                                        "E1",
                                        "测试员工",
                                        10L,
                                        "测试销售部",
                                        "测试城市",
                                        "READY",
                                        1,
                                        i.getArgument(4),
                                        Instant.EPOCH));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    PayrollDetailView calculate() {
        return service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))).getFirst();
    }

    @Test
    void archiveBaseAndIndependentAllowanceAreUsedOnceEvenWithLegacyBaseAndPerformanceFields() {
        var legacyPolicy =
                new PayrollPerformancePolicy(
                        "FIXED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("500"),
                        new BigDecimal("150"),
                        new BigDecimal("100"));
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(
                                view("old-base", rule("BASE", "旧底薪", "9999", null, null, null)),
                                view(
                                        "performance",
                                        rule(
                                                "PERFORMANCE",
                                                "绩效",
                                                "1000",
                                                legacyPolicy,
                                                null,
                                                null)),
                                view(
                                        "allowance",
                                        rule(
                                                "ALLOWANCE",
                                                "固定补贴",
                                                "750",
                                                null,
                                                "FIXED_ALLOWANCE",
                                                new PayrollFixedAllowance(
                                                        new BigDecimal("500"),
                                                        new BigDecimal("150"),
                                                        new BigDecimal("100"))))));
        var value = calculate().calculation();
        assertThat(value.baseSalary()).isEqualByComparingTo("3000");
        assertThat(value.allowance()).isEqualByComparingTo("750");
        assertThat(value.grossPay()).isEqualByComparingTo("4750");
        assertThat(value.issues()).isEmpty();
        assertThat(value.salarySource().probationSalary()).isEqualTo("3000");
        assertThat(value.rules()).noneMatch(r -> "BASE".equals(r.data().kindCode()));
    }

    @Test
    void monthlyAllowanceOverrideAndCorrectionsAreIncludedInSnapshotAndFingerprint() {
        when(store.input(anyString(), eq("E1"), anyString()))
                .thenReturn(
                        new PayrollInputCommand(
                                1,
                                null,
                                new BigDecimal("1000"),
                                BigDecimal.ZERO,
                                "核定月内补贴",
                                new BigDecimal("375")));
        when(store.corrections(anyString(), eq("E1"), anyString()))
                .thenReturn(
                        List.of(
                                new PayrollCorrectionView(
                                        "C1",
                                        "P0",
                                        "2026-08",
                                        "2026-09",
                                        new BigDecimal("-200"),
                                        "冲回",
                                        "ACTIVE",
                                        1,
                                        Instant.EPOCH)));
        var first = calculate().calculation();
        assertThat(first.allowance()).isEqualByComparingTo("375");
        assertThat(first.correctionAmount()).isEqualByComparingTo("-200");
        assertThat(first.netPay()).isEqualByComparingTo("4175");
        assertThat(first.corrections()).hasSize(1);
        when(store.corrections(anyString(), eq("E1"), anyString())).thenReturn(List.of());
        assertThat(calculate().calculation().sourceFingerprint())
                .isNotEqualTo(first.sourceFingerprint());
    }

    @Test
    void formerEmployeeCorrectionsRequireManualWageInputsAndDoNotRequireNewAssignment() {
        when(store.employee(anyString(), eq("E1"), anyString()))
                .thenReturn(
                        new PayrollEmployeeView(
                                "E1",
                                "已离职员工",
                                10L,
                                "销售部",
                                "城市",
                                LocalDate.of(2025, 1, 1),
                                LocalDate.of(2026, 8, 31),
                                "SALES"));
        when(store.departmentAt(anyString(), anyString(), any())).thenReturn(null);
        when(store.corrections(anyString(), eq("E1"), anyString()))
                .thenReturn(
                        List.of(
                                new PayrollCorrectionView(
                                        "C1",
                                        "P0",
                                        "2026-08",
                                        "2026-09",
                                        new BigDecimal("200"),
                                        "补发",
                                        "ACTIVE",
                                        1,
                                        Instant.EPOCH)));
        assertThat(calculate().calculation().issues()).anyMatch(i -> i.contains("非在职月份"));
        when(store.input(anyString(), eq("E1"), anyString()))
                .thenReturn(
                        new PayrollInputCommand(
                                1,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "离职后仅补发差额，其他项核定为0",
                                BigDecimal.ZERO));
        var result = calculate().calculation();
        assertThat(result.issues()).isEmpty();
        assertThat(result.netPay()).isEqualByComparingTo("200");
    }

    @Test
    void batchSkipsLockedRowsBeforeLoadingSources() {
        when(store.byMonth(anyString(), eq("E1"), eq("2026-09")))
                .thenReturn(
                        Optional.of(
                                new PayrollDetailView(
                                        "P1",
                                        "2026-09",
                                        "E1",
                                        "员工",
                                        10L,
                                        "销售部",
                                        "城市",
                                        "CONFIRMED",
                                        2,
                                        null,
                                        Instant.EPOCH)));
        assertThat(service.calculate(new PayrollCalculateCommand("2026-09", List.of("E1"))))
                .isEmpty();
        verifyNoInteractions(source);
        verify(store, never())
                .saveCalculation(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void headquartersCannotReceiveSalesRulesOrTriggerSalesSources() {
        when(store.employee(anyString(), eq("E1"), anyString()))
                .thenReturn(employee("HEADQUARTERS"));
        assertThatThrownBy(this::calculate).hasMessageContaining("仅适用于销售");
        verifyNoInteractions(source);
        verify(store, never())
                .saveCalculation(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void unknownArchiveSalaryDoesNotFallBackToRemovedSalarySettings() {
        when(store.salarySource(anyString(), eq("E1")))
                .thenReturn(
                        new PayrollSalarySourceView(
                                "SALES",
                                false,
                                null,
                                "5000",
                                "3000+1000",
                                LocalDate.of(2026, 1, 1)));
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(view("old-base", rule("BASE", "旧底薪", "9999", null, null, null))));
        var value = calculate().calculation();
        assertThat(value.baseSalary()).isNull();
        assertThat(value.netPay()).isNull();
        assertThat(value.issues()).anyMatch(x -> x.contains("明确金额"));
    }

    @Test
    void fixedAllowanceOverlapIsRejectedEvenIfRuleNamesDiffer() {
        var fixed =
                new PayrollFixedAllowance(
                        new BigDecimal("500"), new BigDecimal("150"), new BigDecimal("100"));
        when(source.dictionary(anyString(), eq("PAYROLL_ALLOWANCE_TYPE")))
                .thenReturn(Set.of("FIXED_ALLOWANCE"));
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(
                                view(
                                        "old",
                                        rule(
                                                "ALLOWANCE",
                                                "旧名",
                                                "750",
                                                null,
                                                "FIXED_ALLOWANCE",
                                                fixed))));
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null,
                                        rule(
                                                "ALLOWANCE",
                                                "另一个名字",
                                                "750",
                                                null,
                                                "FIXED_ALLOWANCE",
                                                fixed)))
                .hasMessageContaining("重叠");
        verify(store, never()).saveRule(anyString(), any(), any(), anyString());
    }

    @Test
    void fixedAllowanceTotalMustEqualItsComponents() {
        var fixed =
                new PayrollFixedAllowance(
                        new BigDecimal("500"), new BigDecimal("150"), new BigDecimal("100"));
        when(source.dictionary(anyString(), eq("PAYROLL_ALLOWANCE_TYPE")))
                .thenReturn(Set.of("FIXED_ALLOWANCE"));
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null,
                                        rule(
                                                "ALLOWANCE",
                                                "固定补贴",
                                                "800",
                                                null,
                                                "FIXED_ALLOWANCE",
                                                fixed)))
                .hasMessageContaining("合计");
    }
}
