package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollComponentStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Isolates beneficiary transfer from the date selecting the commission rate. */
class CommissionTransferCombinationAcceptanceTest {
    final ConfiguredCommissionServiceTest fixture = new ConfiguredCommissionServiceTest();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() {
        fixture.setup();
        when(fixture.store.departmentAt(anyString(), anyString(), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(2);
            return date.isBefore(LocalDate.of(2026, 10, 1)) ? 1L : 2L;
        });
    }

    PayrollComponentVersionView commission(String id, int department, String from, String to,
            String attribution, String standard, String salesRate, String managerRate) {
        var original = fixture.scheme.definition().commission();
        var rules = original.rules().stream()
                .filter(rule -> rule.code().startsWith("NOODLE_"))
                .map(rule -> {
                    ObjectNode node = json.valueToTree(rule);
                    node.put("value", new BigDecimal("SALES".equals(rule.recipientRole()) ? salesRate : managerRate));
                    return json.treeToValue(node, PayrollSchemeCommissionRule.class);
                }).toList();
        return new PayrollComponentVersionView(id, "family-" + department, 1,
                new PayrollComponentDefinition("部门提成", "COMMISSION", "ALL", List.of((long) department),
                        LocalDate.parse(from), LocalDate.parse(to), null, null,
                        new PayrollSchemeCommissionPolicy(attribution, standard, rules), null, null, List.of()),
                "部门交接", "hr", Instant.EPOCH, "PCR-" + id);
    }

    @Test
    void formalCalculationUsesPaidDateStandardForHistoryAndKeepsItsBasis() {
        var catalog = mock(HrPayrollComponentStore.class);
        var original = fixture.scheme;
        fixture.scheme = new PayrollSchemeVersionView(original.id(), original.schemeId(), 1, 1,
                "RESOLVED", original.definition(), original.reason(), original.createdBy(), null, original.updatedTime(), null);
        when(catalog.versions("t")).thenReturn(List.of(commission("new", 2, "2026-10-01", "9999-12-31", "FULLY_PAID_DATE", "FULLY_PAID_DATE", "4", "1")));
        fixture.service.setRules(new PayrollRuleResolver(catalog));
        var facts = List.of(fixture.line("history", 1, "C", true));
        var preview = fixture.calculate(facts, List.of());
        assertThat(preview.issues()).isEmpty();
        assertThat(preview.released()).isEqualByComparingTo("40");
        assertThat(preview.details()).singleElement().satisfies(d -> {
            assertThat(d.sourceEmployeeCode()).isEqualTo("A");
            assertThat(d.trace().schemeVersionId()).isEqualTo("new");
            assertThat(d.message()).contains("历史订单补算", "订单日未配置政策", "保留订单原销售归属")
                    .doesNotContain("试算", "尚未保存工资");
            assertThat(d.trace().orderDate()).isEqualTo(LocalDate.of(2026, 9, 2));
            assertThat(d.trace().fullyPaidDate()).isEqualTo(LocalDate.of(2026, 10, 2));
            assertThat(d.trace().appliedRule().versionId()).isEqualTo("new");
        });
        when(catalog.versions("t")).thenReturn(List.of(commission("order-date", 2, "2026-10-01", "9999-12-31", "ORDER_DATE", "ORDER_DATE", "4", "1")));
        assertThat(fixture.calculate(facts, List.of()).issues())
                .anyMatch(i -> i.contains("历史提成规则待配置"));
        verify(fixture.store, never()).saveCalculation(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOPPED_HISTORY", "CONFLICTING_HISTORY", "EXPIRED_CURRENT", "OTHER_DEPARTMENT", "ORDER_DATE"})
    void uncoveredPoliciesAreNotSilentlyReplaced(String scenario) {
        var catalog = mock(HrPayrollComponentStore.class);
        var original = fixture.scheme;
        fixture.scheme = new PayrollSchemeVersionView(original.id(), original.schemeId(), 1, 1,
                "RESOLVED", original.definition(), original.reason(), original.createdBy(), null, original.updatedTime(), null);
        var current = commission("new", scenario.equals("OTHER_DEPARTMENT") ? 3 : 2,
                "2026-10-01", scenario.equals("EXPIRED_CURRENT") ? "2026-10-01" : "9999-12-31",
                "FULLY_PAID_DATE", scenario.equals("ORDER_DATE") ? "ORDER_DATE" : "FULLY_PAID_DATE", "4", "1");
        var versions = new ArrayList<PayrollComponentVersionView>();
        versions.add(current);
        if (scenario.equals("STOPPED_HISTORY")) {
            var old = commission("old", 1, "2026-09-01", "2026-09-30", "ORDER_DATE", "ORDER_DATE", "8", "2");
            versions.add(new PayrollComponentVersionView(old.id(), old.ruleId(), old.version(), old.definition(),
                    old.reason(), old.createdBy(), old.createdTime(), old.ruleCode(), 2, false,
                    List.of(new PayrollComponentStateChange(1, "2026-09", false, "停用", "hr", Instant.EPOCH))));
        }
        if (scenario.equals("CONFLICTING_HISTORY")) {
            var old = commission("old", 1, "2026-09-01", "2026-09-30", "ORDER_DATE", "ORDER_DATE", "8", "2");
            versions.add(old);
            versions.add(new PayrollComponentVersionView("conflict", "another-family", 1, old.definition(), "冲突", "hr", Instant.EPOCH));
        }
        when(catalog.versions("t")).thenReturn(versions);
        fixture.service.setRules(new PayrollRuleResolver(catalog));
        var result = fixture.calculate(List.of(fixture.line("uncovered", 1, "C", true)), List.of());
        assertThat(result.issues()).isNotEmpty();
        assertThat(result.details()).singleElement().satisfies(d -> {
            assertThat(d.released()).isNull();
            assertThat(d.message()).doesNotContain("历史订单补算");
        });
        if (scenario.equals("STOPPED_HISTORY")) assertThat(result.issues()).anyMatch(i -> i.contains("已停用"));
        if (scenario.equals("CONFLICTING_HISTORY")) assertThat(result.issues()).anyMatch(i -> i.contains("冲突"));
    }

    @Test
    void historicalFallbackDoesNotTurnMissingProductStandardsIntoZeroCommission() {
        var catalog = mock(HrPayrollComponentStore.class);
        var original = fixture.scheme;
        fixture.scheme = new PayrollSchemeVersionView(original.id(), original.schemeId(), 1, 1,
                "RESOLVED", original.definition(), original.reason(), original.createdBy(), null, original.updatedTime(), null);
        when(catalog.versions("t")).thenReturn(List.of(commission("new", 2, "2026-10-01", "9999-12-31", "FULLY_PAID_DATE", "FULLY_PAID_DATE", "4", "1")));
        fixture.service.setRules(new PayrollRuleResolver(catalog));
        var result = fixture.calculate(List.of(fixture.line("eagle", 3, "C", true)), List.of());
        assertThat(result.issues()).anyMatch(i -> i.contains("提成标准未配置"));
        assertThat(result.details()).singleElement().satisfies(d -> {
            assertThat(d.released()).isNull();
            assertThat(d.message()).doesNotContain("历史订单补算");
        });
    }

    @Test
    void tieredSalaryCannotSilentlyApplyTheFixedSalaryMidMonthSwitch() {
        ObjectNode definition = json.valueToTree(PayrollSchemeTestFixture.definition());
        definition.set("personalPolicy", json.valueToTree(new PayrollPersonalPolicy(
                List.of(), null, null, null, null, LocalDate.of(2026, 9, 15),
                new BigDecimal("9000"), new BigDecimal("1600"))));
        assertThat(PayrollSchemeValidator.check(json.treeToValue(definition, PayrollSchemeDefinition.class)).errors())
                .contains("档位薪资请通过版本调整，月内金额切换仅适用固定薪资");
    }

    @ParameterizedTest(name = "manager owner={0}, rate date={1}")
    @CsvSource({
        "ORDER_DATE,ORDER_DATE,50,0,40,10,old",
        "ORDER_DATE,FULLY_PAID_DATE,100,0,80,20,new",
        "FULLY_PAID_DATE,ORDER_DATE,40,10,40,10,old",
        "FULLY_PAID_DATE,FULLY_PAID_DATE,80,20,80,20,new"
    })
    void delayedPaymentAfterDepartmentAndCityManagerHandoverKeepsIndependentPolicyDates(
            String attribution, String standard, String oldEmployeeTotal, String newEmployeeTotal,
            String salesAmount, String managerAmount, String ruleId) {
        var catalog = mock(HrPayrollComponentStore.class);
        var original = fixture.scheme;
        fixture.scheme = new PayrollSchemeVersionView(original.id(), original.schemeId(), 1, 1,
                "RESOLVED", original.definition(), original.reason(), original.createdBy(), null,
                original.updatedTime(), null);
        // The policy in force on the order date chooses owner and economic date. A new department
        // cannot retrospectively change those choices merely because payment happens after transfer.
        when(catalog.versions("t")).thenReturn(List.of(
                commission("old", 1, "2026-09-01", "2026-09-30", attribution, standard, "4", "1"),
                commission("new", 2, "2026-10-01", "9999-12-31", "FULLY_PAID_DATE", "FULLY_PAID_DATE", "8", "2")));
        fixture.service.setRules(new PayrollRuleResolver(catalog));
        var assignments = List.of(
                fixture.assignment("C", "A", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
                fixture.assignment("C", "B", LocalDate.of(2026, 10, 1), LocalDate.MAX));
        var facts = List.of(fixture.line("1", 1, "C", true));
        var before = fixture.calculate(facts, assignments);
        assertThat(before.issues()).isEmpty();
        assertThat(before.released()).isEqualByComparingTo(oldEmployeeTotal);
        assertThat(before.details()).filteredOn(detail -> "SALES".equals(detail.recipientRoleCode()))
                .singleElement().satisfies(detail -> {
                    assertThat(detail.released()).isEqualByComparingTo(salesAmount);
                    assertThat(detail.trace().schemeVersionId()).isEqualTo(ruleId);
                    assertThat(detail.trace().orderDate()).isEqualTo(LocalDate.of(2026, 9, 2));
                    assertThat(detail.trace().fullyPaidDate()).isEqualTo(LocalDate.of(2026, 10, 2));
                });
        fixture.employee = new PayrollEmployeeView("B", "新负责人", 2L, "新部门", null,
                LocalDate.of(2026, 1, 1), null, "CITY_MANAGER");
        var after = fixture.calculate(facts, assignments);
        assertThat(after.issues()).isEmpty();
        assertThat(after.released()).isEqualByComparingTo(newEmployeeTotal);
        var manager = java.util.stream.Stream.concat(before.details().stream(), after.details().stream())
                .filter(detail -> "CITY_MANAGER".equals(detail.recipientRoleCode())).toList();
        assertThat(manager).singleElement().satisfies(detail -> {
            assertThat(detail.released()).isEqualByComparingTo(managerAmount);
            assertThat(detail.trace().schemeVersionId()).isEqualTo(ruleId);
        });
    }
}
