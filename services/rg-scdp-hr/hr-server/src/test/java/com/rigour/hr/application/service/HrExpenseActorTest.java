package com.rigour.hr.application.service;

import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

class HrExpenseActorTest {
    @ParameterizedTest
    @CsvSource({"''", "' '", "'   '"})
    void emptyBusinessPurposeIsRejectedBeforeSaving(String purpose) {
        var details =
                new HrExpenseDetails(
                        "expense", purpose, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null);
        var command =
                new HrExpenseCommand(
                        "request",
                        null,
                        "交通费",
                        "TRAVEL",
                        "EMP1",
                        "4201",
                        "武汉",
                        LocalDate.of(2026, 10, 1),
                        new BigDecimal("35.00"),
                        "UNPAID",
                        null,
                        null,
                        "2026-10",
                        details);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> HrExpenseRules.validate(command, false, 0))
                .hasMessageContaining("费用用途 / 出差事由");
    }

    @ParameterizedTest
    @CsvSource({
        "TRAVEL,expense",
        "ACCOMMODATION,expense",
        "TAXI,expense",
        "OFFICE_SUPPLIES,office",
        "OFFICE_RENT,office",
        "NETWORK,office",
        "FREIGHT,freight",
        "WAREHOUSE_RENT,warehouse",
        "MARKETING,marketing",
        "GOODS_PURCHASE,purchase",
        "CUSTOM_TYPE,expense"
    })
    void classificationIsDerivedFromBusinessTypeInsteadOfCallerInput(String type, String category) {
        var supplied =
                new HrExpenseDetails(
                        "purchase",
                        "门店办理事项",
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
                        null,
                        null,
                        null,
                        null,
                        null);
        var command =
                new HrExpenseCommand(
                        "request",
                        null,
                        "费用事项",
                        type,
                        "EMP1",
                        "4201",
                        "武汉",
                        LocalDate.of(2026, 10, 1),
                        new BigDecimal("35.00"),
                        "UNPAID",
                        null,
                        null,
                        "2026-10",
                        supplied);
        var result = HrExpenseRules.classify(command);
        org.assertj.core.api.Assertions.assertThat(result.expense().costCategory())
                .isEqualTo(category);
        org.assertj.core.api.Assertions.assertThat(result.expense().purpose())
                .isEqualTo(supplied.purpose());
        org.assertj.core.api.Assertions.assertThat(result.amount())
                .isEqualByComparingTo(command.amount());
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void recordsAuthenticatedOperatorWhenEnteringExpenseForAnotherEmployee() {
        UUID tenant = UUID.randomUUID(), operator = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        operator,
                        tenant,
                        operator,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of(),
                        Set.of("hr:expense:write")));
        var store = mock(HrExpenseStore.class);
        var payroll = mock(HrPayrollStore.class);
        var source = mock(HrPayrollSource.class);
        var cities = mock(HrExpenseCitySource.class);
        when(cities.cityName("420100", "hr:expense:write")).thenReturn("武汉市");
        var service =
                new HrExpenseService(
                        store, payroll, source, mock(HrExpenseCostSource.class), cities);
        var employee =
                new PayrollEmployeeView("EMP-OTHER", "报销员工", 10L, "城市部", "武汉", null, null, null);
        when(source.dictionary(tenant.toString(), "REIMBURSEMENT_EXPENSE_TYPE"))
                .thenReturn(Set.of("TRAVEL"));
        when(source.dictionary(tenant.toString(), "COST_PAYMENT_STATUS"))
                .thenReturn(Set.of("UNPAID"));
        when(payroll.employee(tenant.toString(), employee.employeeCode(), "hr:expense:write"))
                .thenReturn(employee);
        var command =
                new HrExpenseCommand(
                        UUID.randomUUID().toString(),
                        null,
                        "客户拜访交通",
                        "TRAVEL",
                        employee.employeeCode(),
                        "420100",
                        "武汉市",
                        LocalDate.of(2026, 10, 17),
                        new BigDecimal("320.00"),
                        "UNPAID",
                        null,
                        null,
                        "2026-10",
                        new HrExpenseDetails(
                                "expense", "武汉门店拜访", null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null));
        service.save(null, command);
        verify(store)
                .save(
                        tenant.toString(),
                        null,
                        HrExpenseRules.classify(command),
                        employee,
                        operator.toString());
    }
}
