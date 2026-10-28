package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.domain.payroll.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class PayrollProcessAssessmentTest {
    final LocalDate day = LocalDate.of(2026, 10, 9);
    final PayrollWorkWindow window =
            new PayrollWorkWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), false, "IGNORE");
    final SalesPerformancePolicy policy =
            new SalesPerformancePolicy(
                    new BigDecimal("1000"),
                    new BigDecimal("1000"),
                    "ASSESS",
                    40,
                    7,
                    new BigDecimal("20"),
                    3,
                    600,
                    new BigDecimal("30"),
                    BigDecimal.ONE,
                    BigDecimal.ONE);

    AttendanceExceptionPeriod period(LocalDateTime start, LocalDateTime end, String kind) {
        return new AttendanceExceptionPeriod(start, end, kind, "核准请假");
    }

    @Test
    void septemberSecondEntryDoesNotPenalizeSeptemberFirstWithoutCheckins() {
        var entry = LocalDate.of(2026, 9, 2);
        var days =
                SalesPerformanceCalculator.processDays(
                        policy,
                        YearMonth.from(entry),
                        entry,
                        null,
                        true,
                        List.of(),
                        Set.of(entry.minusDays(1), entry),
                        "RECORD",
                        Map.of());
        assertThat(days).extracting(d -> d.date()).containsExactly(entry);
        assertThat(days.getFirst().missingCheckins()).isEqualTo(7);
        assertThat(days.getFirst().missingVisits()).isEqualTo(3);
        assertThat(days.getFirst().deduction()).isEqualByComparingTo("230");
    }

    @Test
    void fractionalLeaveDoesNotRoundAnExactTwoVisitsUpToThree() {
        var nineHours =
                new PayrollWorkWindow(LocalTime.of(9, 0), LocalTime.of(18, 0), false, "IGNORE");
        var fractions =
                PayrollProcessAssessment.fractions(
                        nineHours,
                        Set.of(day),
                        List.of(period(day.atTime(9, 0), day.atTime(12, 0), "UNPAID_LEAVE")));
        var result =
                SalesPerformanceCalculator.processDays(
                        policy,
                        YearMonth.from(day),
                        day,
                        null,
                        true,
                        List.of(),
                        Set.of(day),
                        "RECORD",
                        fractions);
        assertThat(result.getFirst().missingVisits()).isEqualTo(2);
    }

    @Test
    void leaveExemptsItsWindowWithoutExemptingOtherDaysOrProratingPerformance() {
        var dates = Set.of(day, day.plusDays(1));
        var fractions =
                PayrollProcessAssessment.fractions(
                        window,
                        dates,
                        List.of(
                                period(
                                        day.atStartOfDay(),
                                        day.plusDays(1).atStartOfDay(),
                                        "UNPAID_LEAVE")));
        var visits =
                List.of(
                        new SalesVisitEvidence(
                                "1", day.plusDays(1), "C1", "APPROVED", 600000L, true));
        var result =
                SalesPerformanceCalculator.calculate(
                        policy,
                        YearMonth.from(day),
                        day.withDayOfMonth(1),
                        null,
                        true,
                        visits,
                        BigDecimal.ONE,
                        dates,
                        BigDecimal.ONE,
                        new BigDecimal("40"),
                        "UNIQUE_CUSTOMER",
                        fractions);
        assertThat(result.days()).hasSize(1);
        assertThat(result.days().getFirst().missingCheckins()).isEqualTo(6);
        assertThat(result.days().getFirst().missingVisits()).isEqualTo(2);
        assertThat(result.processBudget()).isEqualByComparingTo("400");
        assertThat(result.total()).isEqualByComparingTo("820");
    }

    @Test
    void partialLeaveRoundsOnlyRemainingRequirementsUpAndMissingPunchIsNotLeave() {
        var fractions =
                PayrollProcessAssessment.fractions(
                        window,
                        Set.of(day),
                        List.of(period(day.atTime(9, 0), day.atTime(13, 0), "PAID_LEAVE")));
        var result =
                SalesPerformanceCalculator.processDays(
                        policy,
                        YearMonth.from(day),
                        day,
                        null,
                        true,
                        List.of(),
                        Set.of(day),
                        "RECORD",
                        fractions);
        assertThat(result.getFirst().missingCheckins()).isEqualTo(4);
        assertThat(result.getFirst().missingVisits()).isEqualTo(2);
        var missing =
                PayrollProcessAssessment.fractions(
                        window,
                        Set.of(day),
                        List.of(
                                period(
                                        day.atStartOfDay(),
                                        day.plusDays(1).atStartOfDay(),
                                        "MISSING_PUNCH")));
        assertThat(missing.get(day)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void crossMidnightLeaveUsesBusinessWindowAndDoesNotLeakToOtherDates() {
        var night = new PayrollWorkWindow(LocalTime.of(20, 0), LocalTime.of(4, 0), true, "IGNORE");
        var result =
                PayrollProcessAssessment.fractions(
                        night,
                        Set.of(day, day.plusDays(1)),
                        List.of(
                                period(
                                        day.plusDays(1).atStartOfDay(),
                                        day.plusDays(1).atTime(4, 0),
                                        "COMPENSATORY_LEAVE")));
        assertThat(result.get(day)).isEqualByComparingTo("0.5");
        assertThat(result.get(day.plusDays(1))).isEqualByComparingTo("1");
    }
}
