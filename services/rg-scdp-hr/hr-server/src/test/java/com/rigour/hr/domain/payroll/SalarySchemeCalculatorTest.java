package com.rigour.hr.domain.payroll;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class SalarySchemeCalculatorTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void salaryBandsHaveExplicitEndpoints() {
        var tiers =
                List.of(
                        new SalaryBand("BRONZE", d("0"), d("10000"), true, false, d("3000")),
                        new SalaryBand("SILVER", d("10000"), d("15000"), true, true, d("4000")),
                        new SalaryBand("GOLD", d("15000"), null, false, false, d("5000")));
        assertEquals("SILVER", SalarySchemeCalculator.salary(tiers, d("10000"), null).code());
        assertEquals("SILVER", SalarySchemeCalculator.salary(tiers, d("15000"), null).code());
        assertEquals("GOLD", SalarySchemeCalculator.salary(tiers, d("15000.01"), null).code());
        assertEquals("BRONZE", SalarySchemeCalculator.salary(tiers, d("20000"), "BRONZE").code());
    }

    @Test
    void workWindowRetainsCrossMidnightAttributionAndGap() {
        var start = LocalTime.of(9, 0);
        var end = LocalTime.of(6, 0);
        assertEquals(
                LocalDate.of(2026, 10, 31),
                SalarySchemeCalculator.businessDate(
                        LocalDateTime.of(2026, 11, 1, 2, 0), start, end, true, "IGNORE"));
        assertNull(
                SalarySchemeCalculator.businessDate(
                        LocalDateTime.of(2026, 11, 1, 6, 0), start, end, true, "IGNORE"));
        assertEquals(
                LocalDate.of(2026, 11, 1),
                SalarySchemeCalculator.businessDate(
                        LocalDateTime.of(2026, 11, 1, 9, 0), start, end, true, "IGNORE"));
    }

    @Test
    void managerProtectionSplitsCalendarMonthWithoutDoublingSalary() {
        var steps = List.of(new PerformanceBand(d("0"), null, true, false, "LINEAR", d("1")));
        var metrics =
                List.of(new ManagerMetric("R", "回款", d("50"), d("100"), d("100"), steps, d("3")));
        var result =
                SalarySchemeCalculator.manager(
                        d("3100"),
                        metrics,
                        steps,
                        d("3"),
                        YearMonth.of(2026, 10),
                        LocalDate.of(2026, 9, 3),
                        null,
                        LocalDate.of(2026, 10, 3),
                        false,
                        Set.of(),
                        "CALENDAR_DAYS");
        assertTrue(result.issues().isEmpty());
        assertEquals(2, result.protectedDays());
        assertEquals(29, result.assessedDays());
        assertEquals(d("1650.00"), result.amount());
    }

    @Test
    void protectedDaysNeedNoInventedMetricActuals() {
        var result =
                SalarySchemeCalculator.manager(
                        d("1000"),
                        List.of(
                                new ManagerMetric(
                                        "R", "回款", null, d("100"), d("100"), List.of(), d("3"))),
                        List.of(new PerformanceBand(d("0"), null, true, false, "LINEAR", d("1"))),
                        d("3"),
                        YearMonth.of(2026, 9),
                        LocalDate.of(2026, 9, 3),
                        null,
                        LocalDate.of(2026, 10, 3),
                        true,
                        Set.of(),
                        "NONE");
        assertEquals(d("1000.00"), result.amount());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void unverifiedMetricsNeverBecomeZeroPay() {
        var result =
                SalarySchemeCalculator.manager(
                        d("1000"),
                        List.of(
                                new ManagerMetric(
                                        "R", "回款", null, d("100"), d("100"), List.of(), d("3"))),
                        List.of(new PerformanceBand(d("0"), null, true, false, "LINEAR", d("1"))),
                        d("3"),
                        YearMonth.of(2026, 11),
                        LocalDate.of(2026, 9, 3),
                        null,
                        LocalDate.of(2026, 10, 3),
                        false,
                        Set.of(),
                        "NONE");
        assertNull(result.amount());
        assertFalse(result.issues().isEmpty());
    }

    @Test
    void managerCombinesCompletionBeforeApplyingCoefficient() {
        var steps =
                List.of(
                        new PerformanceBand(d("0"), d("1"), true, true, "LINEAR", d("1")),
                        new PerformanceBand(d("1"), null, false, false, "FIXED", d("1.2")));
        var metrics =
                List.of(
                        new ManagerMetric("A", "指标A", d("80"), d("100"), d("50"), List.of(), null),
                        new ManagerMetric(
                                "B", "指标B", d("120"), d("100"), d("50"), List.of(), null));
        var result =
                SalarySchemeCalculator.manager(
                        d("2000"),
                        metrics,
                        steps,
                        d("1.2"),
                        YearMonth.of(2026, 9),
                        LocalDate.of(2026, 1, 1),
                        null,
                        LocalDate.of(2026, 1, 1),
                        false,
                        Set.of(),
                        "NONE");
        assertEquals(0, result.weightedCompletion().compareTo(d("1")));
        assertEquals(0, result.coefficient().compareTo(d("1")));
        assertEquals(d("2000.00"), result.amount());
        var over =
                SalarySchemeCalculator.manager(
                        d("2000"),
                        List.of(
                                new ManagerMetric(
                                        "A", "回款", d("101"), d("100"), d("100"), List.of(), null)),
                        steps,
                        d("1.2"),
                        YearMonth.of(2026, 9),
                        LocalDate.of(2026, 1, 1),
                        null,
                        LocalDate.of(2026, 1, 1),
                        false,
                        Set.of(),
                        "NONE");
        assertEquals(d("2400.00"), over.amount());
    }

    @Test
    void actualCompletionUsesWeightedRateWithoutTierMappingOrCap() {
        var metrics =
                List.of(
                        new ManagerMetric("A", "销售", d("110"), d("100"), d("40"), List.of(), null),
                        new ManagerMetric("B", "回款", d("150"), d("100"), d("60"), List.of(), null));
        var result =
                SalarySchemeCalculator.manager(
                        d("8000"),
                        metrics,
                        List.of(),
                        null,
                        YearMonth.of(2026, 9),
                        LocalDate.of(2026, 1, 1),
                        null,
                        LocalDate.of(2026, 2, 1),
                        false,
                        Set.of(),
                        "CALENDAR_DAYS",
                        "ACTUAL_COMPLETION");
        assertTrue(result.issues().isEmpty());
        assertEquals(0, result.weightedCompletion().compareTo(d("1.34")));
        assertEquals(d("10720.00"), result.amount());
        var uncapped =
                SalarySchemeCalculator.manager(
                        d("4000"),
                        List.of(
                                new ManagerMetric(
                                        "A", "回款", d("400"), d("100"), d("100"), List.of(), null)),
                        List.of(),
                        d("3"),
                        YearMonth.of(2026, 9),
                        LocalDate.of(2026, 1, 1),
                        null,
                        LocalDate.of(2026, 2, 1),
                        false,
                        Set.of(),
                        "NONE",
                        "ACTUAL_COMPLETION");
        assertEquals(d("16000.00"), uncapped.amount());
    }

    @Test
    void personalSalaryChangeAndProtectionAreWeightedTogetherByDate() {
        var month = YearMonth.of(2026, 9);
        var entry = LocalDate.of(2026, 1, 1);
        assertEquals(
                d("10000.00"),
                PersonalSalaryCalculator.managerAmount(
                        d("4000"),
                        d("8000"),
                        LocalDate.of(2026, 9, 16),
                        d("2"),
                        LocalDate.of(2026, 9, 16),
                        month,
                        entry,
                        null,
                        Set.of(),
                        "CALENDAR_DAYS"));
        // 15 days at 80% + 15 days at 100%, not 80% for the entire transition month.
        assertEquals(
                0,
                PersonalSalaryCalculator.average(
                                d("8000"),
                                null,
                                null,
                                LocalDate.of(2026, 9, 16),
                                d("80"),
                                month,
                                entry,
                                null,
                                Set.of(),
                                "CALENDAR_DAYS")
                        .compareTo(d("7200")));
        assertEquals(
                LocalDate.of(2026, 11, 10),
                SalarySchemeCalculator.protectionUntil(LocalDate.of(2026, 10, 10), "MONTHS", 1));
    }
}
