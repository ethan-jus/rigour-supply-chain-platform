package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;
import com.rigour.hr.application.port.out.*;
import com.rigour.order.api.v1.model.OrderCityCollectionSourceView;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class ManagerCityPayrollTest {
    final HrPayrollStore payroll = mock(HrPayrollStore.class);
    final HrPayrollSchemeStore schemes = mock(HrPayrollSchemeStore.class);
    final HrPayrollSource source = mock(HrPayrollSource.class);
    final TargetSettingsStore targets = mock(TargetSettingsStore.class);
    final ConfiguredCommissionService commissions = mock(ConfiguredCommissionService.class);
    final PayrollRuleResolver rules = mock(PayrollRuleResolver.class);
    final JsonMapper json = JsonMapper.builder().build();
    ConfiguredPayrollService service;
    PayrollEmployeeView employee =
            new PayrollEmployeeView(
                    "M", "测试经理", 1L, "A", null, LocalDate.of(2026, 1, 1), null, "CITY_MANAGER");

    @BeforeEach
    void setup() {
        service =
                new ConfiguredPayrollService(
                        payroll, schemes, source, mock(HrAttendanceStore.class), commissions, json);
        service.setRules(rules);
        service.setTargets(targets);
        when(schemes.facts(anyString(), anyString(), anyString()))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                0,
                                null,
                                List.of(
                                        new PayrollMetricActual(
                                                "sales", new BigDecimal("120000"), "两城核定")),
                                "核定"));
        when(payroll.input(anyString(), anyString(), anyString()))
                .thenReturn(new PayrollInputCommand(0, null, null, BigDecimal.ZERO, ""));
        when(payroll.departmentAt(anyString(), anyString(), any())).thenReturn(1L);
        when(payroll.rules("t"))
                .thenReturn(
                        List.of(
                                ManagerCityTargetResolverTest.term(
                                        "M", "A", "2026-01-01", "9999-12-31"),
                                ManagerCityTargetResolverTest.term(
                                        "M", "B", "2026-01-01", "9999-12-31")));
        when(targets.targets("t", "2026-09", "2026-09"))
                .thenReturn(
                        List.of(
                                ManagerCityTargetResolverTest.target("A", "100000", 1),
                                ManagerCityTargetResolverTest.target("B", "50000", 1)));
        when(commissions.calculate(
                        anyString(), any(), any(), any(), anyList(), anyList(), anyList()))
                .thenReturn(
                        new ConfiguredCommissionResult(
                                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of()));
        policy("CITY_MANAGER", "8000", "MANUAL_CONFIRMED", "SALES_AMOUNT");
    }

    void policy(String role, String base, String sourceCode, String metric) {
        ObjectNode node = json.valueToTree(PayrollSchemeTestFixture.definition());
        node.put("role", role);
        node.put("performanceMode", "MANAGER");
        node.put("performanceBase", new BigDecimal(base));
        node.set("allowances", json.createArrayNode());
        node.set(
                "baseSalary",
                json.valueToTree(
                        new PayrollSchemeBaseSalary(
                                "FIXED", new BigDecimal("8000"), List.of(), null)));
        node.set(
                "personalPolicy",
                json.valueToTree(
                        new PayrollPersonalPolicy(
                                List.of(),
                                null,
                                LocalDate.of(2026, 10, 1),
                                new BigDecimal("80"),
                                null,
                                null,
                                null,
                                null)));
        node.set(
                "manager",
                json.valueToTree(
                        new PayrollManagerPolicy(
                                List.of(
                                        new PayrollMetricDefinition(
                                                "sales",
                                                "销售总额",
                                                sourceCode,
                                                null,
                                                new BigDecimal("100"),
                                                List.of(),
                                                null,
                                                metric)),
                                new PayrollProtectionPolicy("MONTHS", 1, true),
                                null,
                                List.of(),
                                "ACTUAL_COMPLETION")));
        var version =
                PayrollSchemeTestFixture.version(
                        json.treeToValue(node, PayrollSchemeDefinition.class));
        when(schemes.versions("t")).thenReturn(List.of(version));
        when(rules.resolve(anyString(), any(), any(), any()))
                .thenReturn(new PayrollRuleMatch(version, List.of()));
    }

    PayrollCalculationView calculate() {
        return service.calculateIfConfigured("t", employee, YearMonth.of(2026, 9)).orElseThrow();
    }

    @Test
    void twoCityGoalsDriveEightyPercentPayAndProbationOnlyDiscountsBase() {
        var result = calculate();
        assertThat(result.issues()).isEmpty();
        assertThat(result.baseSalary()).isEqualByComparingTo("6400");
        assertThat(result.performance()).isEqualByComparingTo("6400");
        assertThat(result.scheme().managerCompletion()).isEqualByComparingTo("0.8");
        assertThat(result.scheme().metrics().getFirst().evidence())
                .contains("100000", "50000", "目标修订1");
    }

    @Test
    void traineeUsesFourThousandPerformanceWithoutDiscountingItByProbation() {
        policy("TRAINEE_MANAGER", "4000", "MANUAL_CONFIRMED", "SALES_AMOUNT");
        var result = calculate();
        assertThat(result.baseSalary()).isEqualByComparingTo("6400");
        assertThat(result.performance()).isEqualByComparingTo("3200");
    }

    @Test
    void targetRevisionInvalidatesConfirmationEvenIfAmountStaysTheSame() {
        var before = calculate();
        when(targets.targets("t", "2026-09", "2026-09"))
                .thenReturn(
                        List.of(
                                ManagerCityTargetResolverTest.target("A", "100000", 2),
                                ManagerCityTargetResolverTest.target("B", "50000", 1)));
        assertThat(calculate().sourceFingerprint()).isNotEqualTo(before.sourceFingerprint());
    }

    @Test
    void protectionAndTermAdjustedGoalIsNotProratedTwice() {
        employee =
                new PayrollEmployeeView(
                        "M",
                        "测试经理",
                        1L,
                        "A",
                        null,
                        LocalDate.of(2026, 8, 11),
                        null,
                        "CITY_MANAGER");
        var result = calculate();
        assertThat(result.issues()).isEmpty();
        assertThat(result.scheme().managerCompletion()).isEqualByComparingTo("1.2");
        assertThat(result.scheme().metrics().getFirst().target()).isEqualByComparingTo("100000");
        assertThat(result.performance()).isEqualByComparingTo("9066.67");
    }

    @Test
    void fullCompletionPaysFullPerformanceBaseWhileOnlySalaryIsDiscounted() {
        when(schemes.facts(anyString(), anyString(), anyString()))
                .thenReturn(
                        new PayrollSchemeFactsView(
                                0,
                                null,
                                List.of(
                                        new PayrollMetricActual(
                                                "sales", new BigDecimal("150000"), "两城核定")),
                                "核定"));
        assertThat(calculate().performance()).isEqualByComparingTo("8000");
        policy("TRAINEE_MANAGER", "4000", "MANUAL_CONFIRMED", "SALES_AMOUNT");
        var result = calculate();
        assertThat(result.baseSalary()).isEqualByComparingTo("6400");
        assertThat(result.performance()).isEqualByComparingTo("4000");
    }

    @Test
    void unavailableReceiptSourceLeavesPerformancePendingInsteadOfZero() {
        policy("CITY_MANAGER", "8000", "CITY_RECEIPTS", "RECEIPT_AMOUNT");
        when(source.cityCollections(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("offline"));
        var result = calculate();
        assertThat(result.performance()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("到账来源暂不可用"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"CITY_RECEIPTS", "FULLY_PAID_RECEIPTS", "MANUAL_CONFIRMED"})
    void allPartialReceiptsCountByReceiptDateWithoutWaitingForFullPayment(String actualSource) {
        policy("CITY_MANAGER", "8000", actualSource, "RECEIPT_AMOUNT");
        when(targets.targets("t", "2026-09", "2026-09"))
                .thenReturn(
                        List.of(
                                new Target(
                                        "2026-09",
                                        "CITY",
                                        "A",
                                        "A",
                                        "RECEIPT_AMOUNT",
                                        new BigDecimal("10000"),
                                        1),
                                new Target(
                                        "2026-09",
                                        "CITY",
                                        "B",
                                        "B",
                                        "RECEIPT_AMOUNT",
                                        new BigDecimal("10000"),
                                        1)));
        when(source.cityCollections("t", "A", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(
                        List.of(
                                new OrderCityCollectionSourceView(
                                        "PAYMENT:1",
                                        "O1",
                                        "A",
                                        LocalDate.of(2026, 9, 5),
                                        new BigDecimal("12000"),
                                        "COMPLETE")));
        when(source.cityCollections("t", "B", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(
                        List.of(
                                new OrderCityCollectionSourceView(
                                        "PAYMENT:2",
                                        "O2",
                                        "B",
                                        LocalDate.of(2026, 9, 7),
                                        new BigDecimal("10000"),
                                        "COMPLETE")));
        assertThat(calculate().performance()).isEqualByComparingTo("8800");
    }

    @Test
    void storeCountCannotAccidentallyUseCityMoneyAsItsActualValue() {
        policy("CITY_MANAGER", "8000", "CITY_RECEIPTS", "ORDERED_STORE_COUNT");
        var result = calculate();
        assertThat(result.performance()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("实际值来源与城市指标不一致"));
        verify(source, never()).cityCollections(anyString(), anyString(), any(), any());
    }
}
