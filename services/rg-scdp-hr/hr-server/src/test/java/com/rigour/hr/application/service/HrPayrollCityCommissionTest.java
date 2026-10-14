package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
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
    void multipleCitiesAggregateOnceAndKeepTheirOwnTermsForCrossMonthCollections() {
        authorize("hr:payroll:calculate");
        when(store.employee(anyString(), eq("M1"), anyString())).thenReturn(manager());
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
                                rule(
                                        "CITY_MANAGER",
                                        null,
                                        "SHIJIAZHUANG",
                                        LocalDate.of(2026, 1, 1),
                                        LocalDate.of(2026, 8, 31)),
                                rule(
                                        "CITY_MANAGER",
                                        null,
                                        "TANGSHAN",
                                        LocalDate.of(2026, 9, 1),
                                        LocalDate.of(2026, 12, 31)),
                                rule("COMMISSION", "CITY_MANAGER", null),
                                rule("COMMISSION", "SALES", null)));
        when(store.departmentAt(anyString(), anyString(), any())).thenReturn(10L);
        var own = cityOrder("OWN", "M1", "SHIJIAZHUANG", "2026-08-20", "100");
        when(source.commissions(anyString(), eq("M1"), isNull(), any())).thenReturn(List.of(own));
        when(source.commissions(anyString(), isNull(), eq("SHIJIAZHUANG"), any()))
                .thenReturn(
                        List.of(
                                cityOrder("S1", "SALES1", "SHIJIAZHUANG", "2026-08-20", "1000"),
                                own,
                                cityOrder(
                                        "AFTER_TERM",
                                        "SALES1",
                                        "SHIJIAZHUANG",
                                        "2026-09-02",
                                        "5000")));
        when(source.commissions(anyString(), isNull(), eq("TANGSHAN"), any()))
                .thenReturn(
                        List.of(
                                cityOrder("S2", "SALES2", "TANGSHAN", "2026-09-02", "2000"),
                                cityOrder(
                                        "BEFORE_TERM",
                                        "SALES2",
                                        "TANGSHAN",
                                        "2026-08-20",
                                        "5000")));
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
                                        "石家庄",
                                        "READY",
                                        1,
                                        i.getArgument(4),
                                        Instant.EPOCH));

        var result =
                service.calculate(new PayrollCalculateCommand("2026-09", List.of("M1")))
                        .getFirst()
                        .calculation();
        // 两城团队 30 + 独立 31 + 本人销售 1；本人订单从两个来源返回也只计一次。
        assertThat(result.commission()).isEqualByComparingTo("62.00");
        assertThat(result.issues()).isEmpty();
        assertThat(result.details()).hasSize(6);
        assertThat(result.details())
                .extracting(d -> d.sourceLineId())
                .containsOnly("S1", "S2", "OWN");
        assertThat(
                        result.details().stream()
                                .filter(d -> "CITY_TEAM".equals(d.recipientRoleCode()))
                                .map(d -> d.released())
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("30.00");
        verify(source).commissions(anyString(), isNull(), eq("SHIJIAZHUANG"), any());
        verify(source).commissions(anyString(), isNull(), eq("TANGSHAN"), any());
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
                                rule("COMMISSION", "CITY_MANAGER", null),
                                rule("COMMISSION", "SALES", null)));
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
        assertThat(saved.calculation().commission()).isEqualByComparingTo("20.00");
        assertThat(saved.calculation().issues()).isEmpty();
        assertThat(saved.calculation().details())
                .extracting(d -> d.recipientRoleCode())
                .containsExactlyInAnyOrder("CITY_MANAGER", "CITY_TEAM");
        assertThat(saved.calculation().details())
                .allSatisfy(d -> assertThat(d.sourceEmployeeCode()).isEqualTo("OTHER_SALES"));
        verify(store)
                .confirmedSalesCommissions(
                        eq(tenant.toString()),
                        eq(Set.of("OTHER_SALES")),
                        eq(Set.of("LINE1")),
                        eq("2026-09"));
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

    @Test
    void integratedCityStandardsOverrideLegacyAndTeamRatiosDifferByCityWithoutOwnDuplication() {
        prepareConfigCalculation();
        var first = configured("C1", "50", List.of(product("QUANTITY", "25")), false);
        var second = configured("C2", "0", List.of(product("PERCENT", "2")), true);
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(
                                first,
                                second,
                                rule("COMMISSION", "SALES", null),
                                rule("COMMISSION", "CITY_MANAGER", null)));
        var own = cityOrder("OWN", "M1", "C1", "2026-08-20", "100");
        when(source.commissions(anyString(), eq("M1"), isNull(), any())).thenReturn(List.of(own));
        when(source.commissions(anyString(), isNull(), eq("C1"), any()))
                .thenReturn(List.of(own, cityOrder("A", "S1", "C1", "2026-08-20", "1000")));
        when(source.commissions(anyString(), isNull(), eq("C2"), any()))
                .thenReturn(List.of(cityOrder("B", "S2", "C2", "2026-09-01", "2000")));
        var result = result();
        assertThat(result.commission()).isEqualByComparingTo("95"); // 25+25+10*50%+2000*2%
        assertThat(result.issues()).isEmpty();
        assertThat(result.details()).hasSize(4);
        assertThat(
                        result.details().stream()
                                .filter(d -> "CITY_TEAM".equals(d.recipientRoleCode()))
                                .toList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.released()).isEqualByComparingTo("5");
                            assertThat(d.message()).contains("50%");
                        });
        assertThat(
                        result.details().stream()
                                .filter(d -> "CITY_MANAGER".equals(d.recipientRoleCode()))
                                .toList())
                .allSatisfy(
                        d -> {
                            assertThat(d.ruleId()).endsWith(":2");
                            assertThat(d.ruleRevision()).isEqualTo(2);
                        });
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(
                                configured("C1", "50", List.of(product("QUANTITY", "25")), true),
                                second,
                                rule("COMMISSION", "SALES", null)));
        assertThat(result().commission()).isEqualByComparingTo("96"); // 本人销售100*1%只加一次
    }

    @Test
    void teamRatioUsesConfirmedSalesAmountAndRuleEditsChangeOnlyNewCalculation() {
        prepareConfigCalculation();
        var first = configured("C1", "50", List.of(product("QUANTITY", "25")), false);
        when(store.rules(anyString()))
                .thenReturn(List.of(first, rule("COMMISSION", "SALES", null)));
        when(source.commissions(anyString(), isNull(), eq("C1"), any()))
                .thenReturn(List.of(cityOrder("A", "S1", "C1", "2026-08-20", "1000")));
        var confirmed =
                new PayrollCommissionDetailView(
                        "A",
                        "SALES",
                        "SO-A",
                        "泡面",
                        "BOX",
                        BigDecimal.ONE,
                        "OLD-SALES",
                        "已确认销售标准",
                        1,
                        new BigDecimal("30"),
                        new BigDecimal("30"),
                        "已确认");
        when(store.confirmedSalesCommissions(anyString(), anySet(), anySet(), anyString()))
                .thenReturn(Map.of("A", confirmed));
        var before = result();
        assertThat(before.commission()).isEqualByComparingTo("40"); // 25+30*50%，不使用新的销售10元
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(
                                configured("C1", "25", List.of(product("QUANTITY", "20")), false),
                                rule("COMMISSION", "SALES", null)));
        var after = result();
        assertThat(after.commission()).isEqualByComparingTo("27.50");
        assertThat(after.sourceFingerprint()).isNotEqualTo(before.sourceFingerprint());
        assertThat(before.commission()).isEqualByComparingTo("40");
        verify(store, never())
                .saveCalculation(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void partialPaymentHoldsConfiguredCommissionAndExplicitNoCommissionDoesNotFallBack() {
        prepareConfigCalculation();
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(
                                configured("C1", "50", List.of(product("QUANTITY", "25")), false),
                                rule("COMMISSION", "SALES", null)));
        var f = cityOrder("A", "S1", "C1", "2026-08-20", "1000");
        var partial =
                new OrderCommissionSourceLineView(
                        f.lineId(),
                        f.orderNo(),
                        f.employeeCode(),
                        f.cityCode(),
                        f.productId(),
                        f.productVariantId(),
                        f.productName(),
                        f.unitCode(),
                        f.orderDate(),
                        f.quantity(),
                        f.lineAmount(),
                        f.orderLineTotal(),
                        f.orderAmount(),
                        new BigDecimal("500"),
                        f.paymentDate(),
                        false,
                        f.qualityStatus());
        when(source.commissions(anyString(), isNull(), eq("C1"), any()))
                .thenReturn(List.of(partial));
        assertThat(result().commission()).isEqualByComparingTo("0");
        assertThat(result().heldCommission()).isEqualByComparingTo("30");
        when(store.rules(anyString()))
                .thenReturn(
                        List.of(
                                configured("C1", "0", List.of(), false),
                                rule("COMMISSION", "SALES", null),
                                rule("COMMISSION", "CITY_MANAGER", null)));
        assertThat(result().commission()).isEqualByComparingTo("0");
        assertThat(result().heldCommission()).isEqualByComparingTo("0");
        assertThat(result().details()).isEmpty();
    }
}
