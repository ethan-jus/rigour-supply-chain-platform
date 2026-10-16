package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
import com.rigour.hr.api.v1.model.PayrollInputCommand;
import com.rigour.hr.api.v1.model.PayrollRuleCommand;
import com.rigour.hr.api.v1.model.PayrollRuleView;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class HrPayrollCityCommissionTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final HrPayrollStore store = mock(HrPayrollStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final HrPayrollService service =
            new HrPayrollService(store, source, mock(ConfiguredPayrollService.class));

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    PayrollRuleView rule(String kind, String role, String city) {
        return rule(kind, role, city, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    }

    PayrollRuleView rule(String kind, String role, String city, LocalDate from, LocalDate to) {
        return new PayrollRuleView(
                kind + "-" + role + "-" + city,
                1,
                new PayrollRuleCommand(
                        UUID.randomUUID().toString(),
                        null,
                        "测试规则",
                        kind,
                        "CITY_MANAGER".equals(kind) ? "M1" : null,
                        "COMMISSION".equals(kind) ? List.of(10L) : List.of(),
                        city,
                        city,
                        true,
                        1L,
                        2L,
                        "泡面",
                        "BOX",
                        "INSTANT_NOODLES",
                        role,
                        "PERCENT",
                        BigDecimal.ONE,
                        null,
                        from,
                        to,
                        true,
                        "",
                        null,
                        null,
                        null),
                Instant.EPOCH);
    }

    void authorize(String permission) {
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
                        Set.of(permission)));
    }

    PayrollEmployeeView manager() {
        return new PayrollEmployeeView(
                "M1", "城市总", 10L, "城市销售部", "石家庄", LocalDate.of(2025, 1, 1), null, "SALES");
    }

    OrderCommissionSourceLineView cityOrder(
            String line, String employee, String city, String date, String amount) {
        var money = new BigDecimal(amount);
        return new OrderCommissionSourceLineView(
                line,
                "SO-" + line,
                employee,
                city,
                1L,
                2L,
                "泡面",
                "BOX",
                LocalDate.parse(date),
                BigDecimal.ONE,
                money,
                money,
                money,
                money,
                LocalDate.of(2026, 9, 15),
                false,
                "COMPLETE");
    }

    @Test
    void sameManagerCanManageDifferentCitiesDuringTheSamePeriodButCannotDuplicateOneCity() {
        authorize("hr:payroll-rule:write");
        when(store.employee(anyString(), eq("M1"), anyString())).thenReturn(manager());
        var shijiazhuang = rule("CITY_MANAGER", null, "SHIJIAZHUANG");
        var tangshan = rule("CITY_MANAGER", null, "TANGSHAN");
        when(store.rules(anyString())).thenReturn(List.of(shijiazhuang));
        when(store.saveRule(anyString(), isNull(), eq(tangshan.data()), anyString()))
                .thenReturn(tangshan);

        assertThat(service.saveRule(null, tangshan.data())).isEqualTo(tangshan);
        assertThatThrownBy(
                        () ->
                                service.saveRule(
                                        null, rule("CITY_MANAGER", null, "SHIJIAZHUANG").data()))
                .hasMessageContaining("重叠");
        verify(store, times(1)).saveRule(anyString(), isNull(), any(), anyString());
    }

    @Test
    void missingCalculatePermissionNeverReadsEmployeeOrSources() {
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
                        Set.of("hr:payroll:read")));
        assertThatThrownBy(
                        () ->
                                service.calculate(
                                        new PayrollCalculateCommand("2026-09", List.of("M1"))))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, source);
    }

    PayrollRuleView configured(
            String city, String percent, List<PayrollCityProductRule> products, boolean own) {
        var original = rule("CITY_MANAGER", null, city);
        var c = original.data();
        var command =
                new PayrollRuleCommand(
                        c.requestId(),
                        null,
                        c.name(),
                        c.kindCode(),
                        c.employeeCode(),
                        c.departmentIds(),
                        c.cityCode(),
                        c.cityName(),
                        own,
                        c.productId(),
                        c.productVariantId(),
                        c.productName(),
                        c.unitCode(),
                        c.groupCode(),
                        c.recipientRoleCode(),
                        c.methodCode(),
                        c.rate(),
                        c.amount(),
                        c.effectiveFrom(),
                        c.effectiveTo(),
                        c.enabled(),
                        c.remark(),
                        null,
                        null,
                        null,
                        c.categoryId(),
                        c.categoryName(),
                        new PayrollCityCommissionPolicy(new BigDecimal(percent), products));
        return new PayrollRuleView(original.id(), 2, command, Instant.EPOCH);
    }

    PayrollCityProductRule product(String method, String rate) {
        return new PayrollCityProductRule(
                1L, 2L, "泡面", "BOX", 3L, "食品", method, new BigDecimal(rate));
    }

    void prepareConfigCalculation() {
        when(store.input(anyString(), eq("M1"), eq("2026-09")))
                .thenReturn(
                        new PayrollInputCommand(
                                1,
                                new BigDecimal("3000"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "测试调整"));
        when(store.departmentAt(anyString(), anyString(), any())).thenReturn(10L);
    }

    PayrollCalculationView result() {
        return service.calculation(tenant.toString(), manager(), YearMonth.of(2026, 9));
    }
}
