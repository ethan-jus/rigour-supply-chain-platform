package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.PayrollCalculateCommand;
import com.rigour.hr.api.v1.model.PayrollDetailView;
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

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class HrPayrollCityCommissionTest {
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final HrPayrollStore store = mock(HrPayrollStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final HrPayrollService service =
            new HrPayrollService(store, source, JsonMapper.builder().build());

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    PayrollRuleView rule(String kind, String role, String city) {
        return new PayrollRuleView(
                kind,
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
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        true,
                        "",
                        null, null, null),
                Instant.EPOCH);
    }

    @Test
    void cityManagerReceivesCommissionFromAnotherSalespersonWithoutOwnOrders() {
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
                        Set.of("hr:payroll:calculate")));
        var employee =
                new PayrollEmployeeView(
                        "M1", "城市总", 10L, "城市销售部", "测试城市", LocalDate.of(2025, 1, 1), null, "SALES");
        when(store.employee(anyString(), eq("M1"), anyString())).thenReturn(employee);
        when(store.input(anyString(), eq("M1"), eq("2026-09")))
                .thenReturn(
                        new PayrollInputCommand(
                                1,
                                new BigDecimal("3000"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "测试调整"));
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(
                                rule("CITY_MANAGER", null, "CITY"),
                                rule("COMMISSION", "CITY_MANAGER", null)));
        when(store.departmentAt(anyString(), anyString(), any())).thenReturn(10L);
        when(source.commissions(anyString(), eq("M1"), isNull(), any())).thenReturn(List.of());
        when(source.commissions(anyString(), isNull(), eq("CITY"), any()))
                .thenReturn(
                        List.of(
                                new OrderCommissionSourceLineView(
                                        "LINE1",
                                        "SO1",
                                        "OTHER_SALES",
                                        "CITY",
                                        1L,
                                        2L,
                                        "泡面",
                                        "BOX",
                                        LocalDate.of(2026, 8, 1),
                                        BigDecimal.ONE,
                                        new BigDecimal("1000"),
                                        new BigDecimal("1000"),
                                        new BigDecimal("1000"),
                                        new BigDecimal("1000"),
                                        LocalDate.of(2026, 9, 1),
                                        false,
                                        "COMPLETE")));
        when(store.released(anyString())).thenReturn(Set.of());
        when(store.saveCalculation(
                        anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenAnswer(
                        i ->
                                new PayrollDetailView(
                                        "P1",
                                        "2026-09",
                                        "M1",
                                        "城市总",
                                        10L,
                                        "城市销售部",
                                        "测试城市",
                                        "READY",
                                        1,
                                        i.getArgument(4),
                                        Instant.EPOCH));
        var saved =
                service.calculate(new PayrollCalculateCommand("2026-09", List.of("M1"))).getFirst();
        assertThat(saved.calculation().commission()).isEqualByComparingTo("10.00");
        assertThat(saved.calculation().issues()).isEmpty();
        assertThat(saved.calculation().details())
                .singleElement()
                .satisfies(d -> assertThat(d.recipientRoleCode()).isEqualTo("CITY_MANAGER"));
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
}
