package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class HrPayrollSalaryProfileTest {
    final HrPayrollStore store = mock(HrPayrollStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final HrPayrollService service =
            new HrPayrollService(store, source, mock(ConfiguredPayrollService.class));

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
