package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class HrPayrollMonthlyDeterminationTest {
    final HrPayrollStore store = mock(HrPayrollStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final HrPayrollService service =
            new HrPayrollService(store, source, JsonMapper.builder().build());
    final String tenant = UUID.randomUUID().toString();
    final PayrollEmployeeView employee =
            new PayrollEmployeeView(
                    "E1", "测试销售", 10L, "档案部门", "测试城市", LocalDate.of(2026, 8, 1), null, "SALES");
    final YearMonth month = YearMonth.of(2026, 9);

    PayrollInputCommand input(Long department) {
        return new PayrollInputCommand(
                0,
                new BigDecimal("4000"),
                new BigDecimal("600"),
                BigDecimal.ZERO,
                "HR核对当月归属及绩效",
                null,
                department);
    }

    PayrollRuleView rule(String kind, String role) {
        return new PayrollRuleView(
                kind + role,
                1,
                new PayrollRuleCommand(
                        UUID.randomUUID().toString(),
                        null,
                        "测试规则",
                        kind,
                        "CITY_MANAGER".equals(kind) ? "M1" : null,
                        List.of(20L),
                        "CITY_MANAGER".equals(kind) ? "CITY" : null,
                        "测试城市",
                        true,
                        1L,
                        2L,
                        "测试泡面",
                        "BOX",
                        "INSTANT_NOODLES",
                        role,
                        "PERCENT",
                        new BigDecimal("4"),
                        new BigDecimal("750"),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        true,
                        "",
                        null,
                        null,
                        null),
                Instant.EPOCH);
    }

    OrderCommissionSourceLineView order(LocalDate date, BigDecimal paid, boolean refund) {
        return new OrderCommissionSourceLineView(
                "L1",
                "SO1",
                "E1",
                "CITY",
                1L,
                2L,
                "测试泡面",
                "BOX",
                date,
                BigDecimal.ONE,
                new BigDecimal("1000"),
                new BigDecimal("1000"),
                new BigDecimal("1000"),
                paid,
                LocalDate.of(2026, 9, 20),
                refund,
                "COMPLETE");
    }

    @BeforeEach
    void setup() {
        UUID actor = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        actor,
                        UUID.fromString(tenant),
                        actor,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:payroll:calculate")));
        when(store.input(anyString(), anyString(), anyString()))
                .thenReturn(new PayrollInputCommand(0, null, null, BigDecimal.ZERO, ""));
        when(store.input(tenant, "E1", "2026-09")).thenReturn(input(20L));
        when(store.departmentAt(anyString(), anyString(), any())).thenReturn(null);
        when(store.employee(tenant, "E1", "hr:payroll:calculate")).thenReturn(employee);
        when(store.departments(tenant, "hr:payroll:calculate"))
                .thenReturn(List.of(new PayrollDepartmentView(20L, null, "核定部门", 0)));
        when(store.rules(tenant))
                .thenReturn(List.of(rule("COMMISSION", "SALES"), rule("ALLOWANCE", null)));
        when(source.commissions(eq(tenant), eq("E1"), isNull(), any()))
                .thenReturn(List.of(order(month.atDay(5), new BigDecimal("1000"), false)));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void monthlyDepartmentAndPerformanceResolveMissingAssignmentWithoutTouchingSources() {
        var result = service.calculation(tenant, employee, month);
        assertThat(result.issues()).isEmpty();
        assertThat(result.performance()).isEqualByComparingTo("600");
        assertThat(result.commission()).isEqualByComparingTo("40");
        assertThat(result.allowance()).isEqualByComparingTo("750");
        assertThat(result.netPay()).isEqualByComparingTo("5390");
        assertThat(result.input().departmentOverrideId()).isEqualTo(20L);
        assertThat(employee.departmentId()).isEqualTo(10L);
        verify(source, never()).performanceEvidence(anyString(), anyString(), any(), any());
        verify(store, never()).saveInput(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void septemberDeterminationDoesNotChangeAugustOrdersOrOtherMonths() {
        when(source.commissions(eq(tenant), eq("E1"), isNull(), any()))
                .thenReturn(
                        List.of(order(LocalDate.of(2026, 8, 5), new BigDecimal("1000"), false)));
        assertThat(service.calculation(tenant, employee, month).issues())
                .contains("SO1：订单发生时的部门归属待核对");
        when(store.input(tenant, "E1", "2026-10")).thenReturn(input(null));
        assertThat(service.calculation(tenant, employee, YearMonth.of(2026, 10)).issues())
                .contains("当月任职部门资料缺失或发生调动，请核对期间归属");
    }

    @Test
    void cityManagerUsesSalespersonsDeterminationRatherThanManagersOwnDepartment() {
        var manager =
                new PayrollEmployeeView(
                        "M1",
                        "测试城市总",
                        10L,
                        "档案部门",
                        "测试城市",
                        LocalDate.of(2026, 8, 1),
                        null,
                        "SALES");
        when(store.input(tenant, "M1", "2026-09")).thenReturn(input(30L));
        when(store.rules(tenant))
                .thenReturn(
                        List.of(rule("CITY_MANAGER", null), rule("COMMISSION", "CITY_MANAGER")));
        when(source.commissions(eq(tenant), isNull(), eq("CITY"), any()))
                .thenReturn(List.of(order(month.atDay(5), new BigDecimal("1000"), false)));
        var result = service.calculation(tenant, manager, month);
        assertThat(result.issues()).isEmpty();
        assertThat(result.commission()).isEqualByComparingTo("40");
        assertThat(result.details())
                .singleElement()
                .satisfies(d -> assertThat(d.recipientRoleCode()).isEqualTo("CITY_MANAGER"));
    }

    @Test
    void manualDeterminationStillRequiresFullPaymentAndRefundReview() {
        when(source.commissions(eq(tenant), eq("E1"), isNull(), any()))
                .thenReturn(List.of(order(month.atDay(5), new BigDecimal("500"), false)));
        var result = service.calculation(tenant, employee, month);
        assertThat(result.commission()).isEqualByComparingTo("0");
        assertThat(result.heldCommission()).isEqualByComparingTo("40");
        when(source.commissions(eq(tenant), eq("E1"), isNull(), any()))
                .thenReturn(List.of(order(month.atDay(5), new BigDecimal("1000"), true)));
        assertThat(service.calculation(tenant, employee, month).issues())
                .contains("SO1：存在退款，需核对净销售与冲回");
    }

    @Test
    void changingOrClearingDeterminationChangesFingerprintAndRestoresReview() {
        var result = service.calculation(tenant, employee, month);
        when(store.input(tenant, "E1", "2026-09")).thenReturn(input(null));
        var restored = service.calculation(tenant, employee, month);
        assertThat(restored.sourceFingerprint()).isNotEqualTo(result.sourceFingerprint());
        assertThat(restored.issues()).contains("SO1：订单发生时的部门归属待核对");
    }

    @Test
    void departmentMustBeAvailableInCurrentTenantsAuthorizedScope() {
        service.saveInput("2026-09", "E1", input(20L));
        verify(store).saveInput(eq(tenant), eq("E1"), eq("2026-09"), eq(input(20L)), anyString());
        clearInvocations(store);
        assertThatThrownBy(() -> service.saveInput("2026-09", "E1", input(99L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("核定部门");
        verify(store, never()).saveInput(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void manualAmountZeroIsExplicitAndMissingReasonCannotBeSaved() {
        var zero =
                new PayrollInputCommand(
                        0,
                        new BigDecimal("4000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "核定绩效为零",
                        null,
                        20L);
        when(store.input(tenant, "E1", "2026-09")).thenReturn(zero);
        assertThat(service.calculation(tenant, employee, month).performance())
                .isEqualByComparingTo("0");
        assertThatThrownBy(
                        () ->
                                service.saveInput(
                                        "2026-09",
                                        "E1",
                                        new PayrollInputCommand(
                                                0,
                                                null,
                                                BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                "",
                                                null,
                                                20L)))
                .isInstanceOf(BusinessException.class);
    }
}
