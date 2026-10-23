package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class ManagerCityTargetResolverTest {
    static final YearMonth MONTH = YearMonth.of(2026, 9);

    static PayrollRuleView term(String employee, String city, String from, String to) {
        var json = JsonMapper.builder().build();
        var data =
                json.readValue(
                        """
{"employeeCode":"%s","kindCode":"CITY_MANAGER","cityCode":"%s","cityName":"%s",
 "effectiveFrom":"%s","effectiveTo":"%s","enabled":true}
"""
                                .formatted(employee, city, city, from, to),
                        PayrollRuleCommand.class);
        return new PayrollRuleView(employee + city + from, 1, data, Instant.EPOCH);
    }

    static Target target(String city, String value, int revision) {
        return new Target(
                "2026-09", "CITY", city, city, "SALES_AMOUNT", new BigDecimal(value), revision);
    }

    private com.rigour.hr.application.model.ManagerCityTarget resolve(
            List<PayrollRuleView> terms, List<Target> goals, LocalDate from) {
        return ManagerCityTargetResolver.resolve(
                "M",
                "SALES_AMOUNT",
                MONTH,
                LocalDate.of(2026, 1, 1),
                null,
                from,
                Set.of(),
                "NONE",
                terms,
                goals);
    }

    @Test
    void sumsCityGoalsAndKeepsTheirIndividualRevisions() {
        var result =
                resolve(
                        List.of(
                                term("M", "A", "2026-01-01", "9999-12-31"),
                                term("M", "B", "2026-01-01", "9999-12-31")),
                        List.of(target("A", "100000", 2), target("B", "50000", 7)),
                        MONTH.atDay(1));
        assertThat(result.issues()).isEmpty();
        assertThat(result.amount()).isEqualByComparingTo("150000");
        assertThat(result.evidence()).contains("目标修订2", "目标修订7", "30/30");
    }

    @Test
    void splitsAChangedTermAndProtectionExactlyOnce() {
        var result =
                resolve(
                        List.of(
                                term("M", "A", "2026-09-01", "2026-09-15"),
                                term("M", "B", "2026-09-16", "9999-12-31")),
                        List.of(target("A", "30000", 1), target("B", "60000", 1)),
                        MONTH.atDay(11));
        assertThat(result.amount()).isEqualByComparingTo("35000");
        assertThat(result.evidence()).contains("5/30", "15/30");
    }

    @Test
    void neverUsesPersonalOrOtherCityGoalsToHideAMissingCity() {
        var result =
                resolve(
                        List.of(term("M", "A", "2026-01-01", "9999-12-31")),
                        List.of(
                                new Target(
                                        "2026-09",
                                        "SALES_OWNER",
                                        "M",
                                        "M",
                                        "SALES_AMOUNT",
                                        new BigDecimal("10000"),
                                        1),
                                target("B", "20000", 1)),
                        MONTH.atDay(1));
        assertThat(result.amount()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("城市指标设置"));
    }

    @Test
    void overlappingManagersCannotReceiveTheSameCityGoalSilently() {
        var result =
                resolve(
                        List.of(
                                term("M", "A", "2026-01-01", "9999-12-31"),
                                term("OTHER", "A", "2026-09-15", "9999-12-31")),
                        List.of(target("A", "30000", 1)),
                        MONTH.atDay(1));
        assertThat(result.amount()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.contains("任期重叠"));
    }

    @Test
    void workdayDenominatorRespectsDepartmentCalendarAndFutureTermsAreIgnored() {
        var days = Set.of(MONTH.atDay(1), MONTH.atDay(2), MONTH.atDay(3), MONTH.atDay(4));
        var result =
                ManagerCityTargetResolver.resolve(
                        "M",
                        "SALES_AMOUNT",
                        MONTH,
                        MONTH.atDay(2),
                        null,
                        MONTH.atDay(1),
                        days,
                        "WORK_DAYS",
                        List.of(
                                term("M", "A", "2026-09-01", "2026-09-03"),
                                term("M", "B", "2026-10-01", "9999-12-31")),
                        List.of(target("A", "10000", 1)));
        assertThat(result.issues()).isEmpty();
        assertThat(result.amount()).isEqualByComparingTo("5000");
    }

    @Test
    void fullyProtectedMonthNeedsNoInventedGoal() {
        assertThat(resolve(List.of(), List.of(), LocalDate.of(2026, 10, 1)).amount()).isZero();
    }

    @Test
    void mapsDifferentCrmAndHrCodesOnlyThroughAUniqueCityName() {
        var term = term("M", "CRM-BEIJING", "2026-01-01", "9999-12-31");
        var json = JsonMapper.builder().build();
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(term.data());
        node.put("cityName", "北京市");
        term =
                new PayrollRuleView(
                        "x", 1, json.treeToValue(node, PayrollRuleCommand.class), Instant.EPOCH);
        var goal =
                new Target(
                        "2026-09",
                        "CITY",
                        "HR-BEIJING",
                        "北京",
                        "SALES_AMOUNT",
                        new BigDecimal("10000"),
                        3);
        var result = resolve(List.of(term), List.of(goal), MONTH.atDay(1));
        assertThat(result.amount()).isEqualByComparingTo("10000");
        assertThat(result.sources()).containsExactly(goal);
        var duplicate =
                new Target(
                        "2026-09",
                        "CITY",
                        "OTHER",
                        "北京市",
                        "SALES_AMOUNT",
                        new BigDecimal("20000"),
                        1);
        assertThat(resolve(List.of(term), List.of(goal, duplicate), MONTH.atDay(1)).amount())
                .isNull();
    }
}
