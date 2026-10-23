package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class HrExpenseCityValidationTest {
    final UUID tenant = UUID.randomUUID();
    final HrExpenseStore store = mock(HrExpenseStore.class);
    final HrPayrollStore payroll = mock(HrPayrollStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final HrExpenseCitySource cities = mock(HrExpenseCitySource.class);
    final HrExpenseService service =
            new HrExpenseService(store, payroll, source, mock(HrExpenseCostSource.class), cities);

    @BeforeEach
    void setup() {
        var operator = UUID.randomUUID();
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
                        Set.of("hr:expense:read", "hr:expense:write", "hr:expense:confirm")));
        when(source.dictionary(tenant.toString(), "REIMBURSEMENT_EXPENSE_TYPE"))
                .thenReturn(Set.of("TRAVEL"));
        when(source.dictionary(tenant.toString(), "COST_PAYMENT_STATUS"))
                .thenReturn(Set.of("UNPAID"));
        when(cities.cityName("420100", "hr:expense:write")).thenReturn("武汉市");
        when(cities.cityName("420100", "hr:expense:read")).thenReturn("武汉市");
        when(cities.cityName("420100", "hr:expense:confirm")).thenReturn("武汉市");
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void rejectsUnknownOrOutOfScopeCityBeforeAnyExpenseIsWritten() {
        when(cities.cityName("UNKNOWN", "hr:expense:write"))
                .thenThrow(new AuthorizationDeniedException("hr:expense:write"));
        assertThatThrownBy(() -> service.save(null, command("UNKNOWN", "武汉市")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, payroll);
    }

    @Test
    void rejectsForgedDisplayNameEvenWhenCityCodeIsAuthorized() {
        assertThatThrownBy(() -> service.save(null, command("420100", "北京市")))
                .hasMessageContaining("城市名称与授权目录不一致");
        verifyNoInteractions(store, payroll);
    }

    @Test
    void crossCityBusinessExpenseRetainsAuthorizedBeneficiaryCity() {
        var employee =
                new PayrollEmployeeView("EMP1", "跨城出差员工", 10L, "北京销售部", "北京市", null, null, null);
        when(payroll.employee(tenant.toString(), "EMP1", "hr:expense:write")).thenReturn(employee);
        var command = command("420100", "武汉市");
        service.save(null, command);
        verify(store)
                .save(
                        eq(tenant.toString()),
                        isNull(),
                        eq(HrExpenseRules.classify(command)),
                        eq(employee),
                        anyString());
    }

    @Test
    void revokedEmployeePermissionCannotBeBypassedByValidCity() {
        when(payroll.employee(tenant.toString(), "EMP1", "hr:expense:write"))
                .thenThrow(new AuthorizationDeniedException("hr:expense:write"));
        assertThatThrownBy(() -> service.save(null, command("420100", "武汉市")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void confirmationRechecksCurrentCityPermissionForHistoricalDraft() {
        var draft =
                new HrExpenseDetailView(
                        "expense-id",
                        "EXP1",
                        "DRAFT",
                        1,
                        command("420100", "武汉市"),
                        "员工",
                        1L,
                        "销售部",
                        null);
        when(store.expenseDetail(tenant.toString(), "expense-id", "hr:expense:confirm"))
                .thenReturn(draft);
        when(cities.cityName("420100", "hr:expense:confirm"))
                .thenThrow(new AuthorizationDeniedException("hr:expense:confirm"));
        assertThatThrownBy(
                        () -> service.action("expense-id", new PayrollActionCommand(1, null), true))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(store, never())
                .expenseTransition(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void cityDirectoryFailureNeverFallsBackToCallerSuppliedCity() {
        when(cities.cityName("420100", "hr:expense:write"))
                .thenThrow(new IllegalStateException("授权城市目录暂不可用"));
        assertThatThrownBy(() -> service.save(null, command("420100", "武汉市")))
                .hasMessageContaining("授权城市目录暂不可用");
        verifyNoInteractions(store, payroll);
    }

    @Test
    void employeePickerCannotUseForgedCityName() {
        assertThatThrownBy(() -> service.employees("420100", "北京市", LocalDate.of(2026, 10, 22)))
                .hasMessageContaining("城市名称与授权目录不一致");
        verifyNoInteractions(payroll);
    }

    @Test
    void headquartersPickerUsesOnlyOriginalEmployeeReadScopeWithoutGeographicFilter() {
        when(cities.cityName("COST_HEADQUARTERS", "hr:expense:read")).thenReturn("总部");
        var permitted =
                List.of(
                        new PayrollEmployeeView(
                                "EMP1", "可见员工", 10L, "北京销售部", "北京市", null, null, null));
        when(payroll.employees(tenant.toString(), "hr:expense:read")).thenReturn(permitted);
        assertThat(service.employees("COST_HEADQUARTERS", "总部", LocalDate.of(2026, 10, 22)))
                .isSameAs(permitted);
        verify(payroll).employees(tenant.toString(), "hr:expense:read");
        verify(payroll, never())
                .expenseEmployees(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void headquartersEmployeeLookupStillRequiresAuthorizedHeadquarters() {
        when(cities.cityName("COST_HEADQUARTERS", "hr:expense:read"))
                .thenThrow(new AuthorizationDeniedException("hr:expense:read"));
        assertThatThrownBy(
                        () ->
                                service.employees(
                                        "COST_HEADQUARTERS", "总部", LocalDate.of(2026, 10, 22)))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(payroll);
    }

    @Test
    void operationalCityEmployeeLookupKeepsCityAndEffectiveDateFilter() {
        var date = LocalDate.of(2026, 10, 22);
        service.employees("420100", "武汉市", date);
        verify(payroll)
                .expenseEmployees(tenant.toString(), "hr:expense:read", "420100", "武汉市", date);
        verify(payroll, never()).employees(anyString(), anyString());
    }

    @Test
    void writePermissionIsStillRequiredBeforeCityLookup() {
        var operator = UUID.randomUUID();
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
                        Set.of("hr:expense:read")));
        assertThatThrownBy(() -> service.save(null, command("420100", "武汉市")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(cities, store, payroll, source);
    }

    private HrExpenseCommand command(String cityCode, String cityName) {
        return new HrExpenseCommand(
                UUID.randomUUID().toString(),
                null,
                "武汉门店培训差旅",
                "TRAVEL",
                "EMP1",
                cityCode,
                cityName,
                LocalDate.of(2026, 10, 22),
                new BigDecimal("320.00"),
                "UNPAID",
                null,
                null,
                "2026-10",
                new HrExpenseDetails(
                        "expense",
                        "北京员工赴武汉开展门店培训",
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
                        null));
    }
}
