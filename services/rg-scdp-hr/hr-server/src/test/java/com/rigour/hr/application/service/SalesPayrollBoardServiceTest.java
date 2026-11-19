package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.hr.infrastructure.persistence.repository.SalesStatementPublications;
import com.rigour.order.api.v1.model.OrderPayrollLineView;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class SalesPayrollBoardServiceTest {
    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void keepsLockedOrderBasisAndWageSeparateAndDeduplicatesProductLines() {
        var month = YearMonth.of(2026, 10);
        var employee = mock(PayrollEmployeeView.class);
        when(employee.employeeCode()).thenReturn("A");
        var line = mock(OrderPayrollLineView.class);
        when(line.orderId()).thenReturn("1");
        when(line.orderNo()).thenReturn("S1");
        when(line.qualityStatus()).thenReturn("COMPLETE");
        when(line.fullyPaidDate()).thenReturn(month.atDay(12));
        when(line.orderAmount()).thenReturn(new BigDecimal("6000"));
        var sep =
                SalesPayrollBoardService.employee(
                        month.minusMonths(1), null, employee, List.of(line, line));
        var oct = SalesPayrollBoardService.employee(month, null, employee, List.of(line, line));
        var nov =
                SalesPayrollBoardService.employee(
                        month.plusMonths(1), null, employee, List.of(line));
        assertThat(sep.basis()).isZero();
        assertThat(oct.basis()).isEqualByComparingTo("6000");
        assertThat(oct.orders()).hasSize(1);
        assertThat(nov.basis()).isZero();
        assertThat(oct.commission()).isNull();
        var calculation = mock(PayrollCalculationView.class);
        var row = mock(PayrollDetailView.class);
        when(row.calculation()).thenReturn(calculation);
        when(row.employeeCode()).thenReturn("A");
        when(row.statusCode()).thenReturn("CONFIRMED");
        when(calculation.netPay()).thenReturn(new BigDecimal("5500"));
        when(calculation.settledOrders()).thenReturn(List.of());
        var locked = SalesPayrollBoardService.employee(month, row, null, List.of(line));
        assertThat(locked.net()).isEqualByComparingTo("5500");
        assertThat(locked.basis()).isZero();
        assertThat(locked.paid()).isZero();
        when(calculation.settledOrders())
                .thenReturn(
                        List.of(
                                new PayrollCollectionEvidenceView(
                                        "FULL:1",
                                        "S1",
                                        month.atDay(12),
                                        new BigDecimal("6000"),
                                        "FULLY_PAID")));
        when(calculation.commission()).thenReturn(new BigDecimal("120"));
        var confirmed = SalesPayrollBoardService.employee(month, row, null, List.of());
        assertThat(confirmed.basis()).isEqualByComparingTo("6000");
        assertThat(confirmed.commission()).isEqualByComparingTo("120");
    }

    @Test
    void confirmedMonthlyWagesRemainPayableWithZeroOrDelayedCommission() {
        var september = YearMonth.of(2026, 9);
        var october = september.plusMonths(1);
        var row = mock(PayrollDetailView.class);
        var c = mock(PayrollCalculationView.class);
        var performance = mock(PayrollPerformanceResultView.class);
        when(row.employeeCode()).thenReturn("A");
        when(row.employeeName()).thenReturn("销售甲");
        when(row.cityName()).thenReturn("杭州");
        when(row.departmentName()).thenReturn("销售部");
        when(row.revision()).thenReturn(1);
        when(row.statusCode()).thenReturn("CONFIRMED");
        when(row.updatedTime()).thenReturn(Instant.parse("2026-11-01T01:00:00Z"));
        when(row.calculation()).thenReturn(c);
        when(c.performanceDetail()).thenReturn(performance);
        when(performance.processPay()).thenReturn(new BigDecimal("800"));
        when(performance.collectionTarget()).thenReturn(new BigDecimal("6000"));
        when(c.performance()).thenReturn(new BigDecimal("800"));
        when(c.baseSalary()).thenReturn(new BigDecimal("5000"));
        when(c.allowance()).thenReturn(new BigDecimal("200"));
        when(c.deduction()).thenReturn(new BigDecimal("500"));
        when(c.heldCommission()).thenReturn(BigDecimal.ZERO);
        when(c.correctionAmount()).thenReturn(BigDecimal.ZERO);
        when(c.settledOrders()).thenReturn(List.of());
        when(c.commission()).thenReturn(BigDecimal.ZERO);
        when(c.grossPay()).thenReturn(new BigDecimal("6000"));
        when(c.netPay()).thenReturn(new BigDecimal("5500"));
        var before = SalesPayrollBoardService.employee(september, row, null, List.of());
        assertThat(before.basis()).isZero();
        assertThat(before.commission()).isZero();
        assertThat(before.net()).isEqualByComparingTo("5500");
        assertThat(before.paid()).isZero();
        when(c.settledOrders())
                .thenReturn(
                        List.of(
                                new PayrollCollectionEvidenceView(
                                        "FULL:1",
                                        "S1",
                                        october.atDay(12),
                                        new BigDecimal("6000"),
                                        "FULLY_PAID")));
        when(c.commission()).thenReturn(new BigDecimal("120"));
        when(c.grossPay()).thenReturn(new BigDecimal("6120"));
        when(c.netPay()).thenReturn(new BigDecimal("5620"));
        var detail = mock(PayrollCommissionDetailView.class);
        when(detail.orderNo()).thenReturn("S1");
        when(detail.productName()).thenReturn("示例商品");
        when(detail.ruleName()).thenReturn("示例规则 2%");
        when(detail.ruleRevision()).thenReturn(1);
        when(detail.quantity()).thenReturn(BigDecimal.ONE);
        when(detail.accrued()).thenReturn(new BigDecimal("120"));
        when(detail.released()).thenReturn(new BigDecimal("120"));
        when(c.details()).thenReturn(List.of(detail));
        var after = SalesPayrollBoardService.employee(october, row, null, List.of());
        assertThat(after.basis()).isEqualByComparingTo("6000");
        assertThat(after.net()).isEqualByComparingTo("5620");
        assertThat(after.withPayment(new BigDecimal("300")).status()).isEqualTo("CONFIRMED");
        assertThat(after.withPayment(new BigDecimal("5620")).status()).isEqualTo("PAID");
    }

    @Test
    void personalReadsUseOnlyBoundEmployeeAndExplicitPublication() {
        String action = "hr:payroll:self";
        var tenant = UUID.randomUUID();
        var user = UUID.randomUUID();
        var actor =
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
                        Set.of(action));
        TestAuthorizationContext.set(actor);
        var iam = mock(SupplyAuthorizationClient.class);
        var policy = mock(SupplyAuthorizationView.class);
        when(iam.authorization(actor, action)).thenReturn(policy);
        when(policy.mode()).thenReturn("ACTIVE");
        when(policy.functionAllowed()).thenReturn(true);
        when(policy.tenantId()).thenReturn(tenant);
        when(policy.userId()).thenReturn(user);
        when(policy.action()).thenReturn(action);
        when(policy.employeeCode()).thenReturn("A");
        var store = mock(HrPayrollStore.class);
        var published = mock(SalesStatementPublications.class);
        var service =
                new SalesPayrollBoardService(store, mock(HrPayrollSource.class), iam, published);
        assertThat(service.personal("2026-10").employees()).isEmpty();
        verify(published).published(tenant.toString(), "A", "2026-10");
        verifyNoInteractions(store);
        when(policy.employeeCode()).thenReturn(null);
        assertThatThrownBy(() -> service.personal("2026-10"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }
}
