package com.rigour.hr.domain.payroll;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

class SalesPerformanceCalculatorTest {
    private BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    // Test-only amounts are arbitrary examples, not seeded business salary standards.
    private SalesPerformancePolicy policy() {
        return new SalesPerformancePolicy(
                n("1000"),
                n("800"),
                "EXEMPT_ALL",
                40,
                7,
                n("20"),
                3,
                600,
                n("30"),
                n("5000"),
                n("7000"));
    }

    private List<SalesVisitEvidence> perfect(YearMonth m) {
        List<SalesVisitEvidence> result = new ArrayList<>();
        for (int d = 1; d <= m.lengthOfMonth(); d++)
            for (int c = 0; c < 7; c++)
                result.add(
                        new SalesVisitEvidence(
                                d + "-" + c,
                                m.atDay(d),
                                "C" + c,
                                "APPROVED",
                                c < 3 ? 600000L : 1000L,
                                true));
        return result;
    }

    @Test
    void firstNaturalMonthExemptsEverythingEvenWithoutSalesEvidence() {
        var r =
                SalesPerformanceCalculator.calculate(
                        policy(),
                        YearMonth.of(2026, 9),
                        LocalDate.of(2026, 9, 28),
                        null,
                        false,
                        List.of(),
                        null);
        assertThat(r.total()).isEqualByComparingTo("800");
        assertThat(r.issues()).isEmpty();
        assertThat(r.days()).isEmpty();
    }

    @Test
    void secondMonthCollectionIsProratedAndThirdMonthUsesSevenThousand() {
        var m = YearMonth.of(2026, 9);
        var second =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 31), null, true, perfect(m), n("2500"));
        assertThat(second.processPay()).isEqualByComparingTo("400");
        assertThat(second.collectionTarget()).isEqualByComparingTo("5000");
        assertThat(second.collectionPay()).isEqualByComparingTo("300");
        assertThat(second.total()).isEqualByComparingTo("700");
        var third =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 7, 1), null, true, perfect(m), n("3500"));
        assertThat(third.collectionTarget()).isEqualByComparingTo("7000");
        assertThat(third.total()).isEqualByComparingTo("700");
    }

    @Test
    void saturdayAndSundayCountAndDeductionsCannotReduceCollectionPerformance() {
        var m = YearMonth.of(2026, 9);
        var r =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 1), null, true, List.of(), n("9999"));
        assertThat(r.days()).hasSize(30);
        assertThat(r.processDeduction()).isEqualByComparingTo("6900");
        assertThat(r.processPay()).isEqualByComparingTo("0");
        assertThat(r.collectionPay()).isEqualByComparingTo("600");
        assertThat(r.total()).isEqualByComparingTo("600");
    }

    @Test
    void exactlyTenMinutesQualifiesButShortDuplicateDoesNot() {
        var m = YearMonth.of(2026, 9);
        var evidence = new ArrayList<>(perfect(m));
        evidence.removeIf(v -> v.date().equals(m.atDay(1)));
        for (int c = 0; c < 7; c++)
            evidence.add(
                    new SalesVisitEvidence(
                            "S" + c,
                            m.atDay(1),
                            "C" + c,
                            "APPROVED",
                            c == 0 ? 600000L : c == 1 ? 599999L : c == 2 ? 600001L : 0L,
                            true));
        evidence.add(new SalesVisitEvidence("DUP", m.atDay(1), "C0", "APPROVED", 600000L, true));
        var r =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 1), null, true, evidence, n("5000"));
        assertThat(r.days().getFirst().checkins()).isEqualTo(7);
        assertThat(r.days().getFirst().effectiveVisits()).isEqualTo(2);
        assertThat(r.processDeduction()).isEqualByComparingTo("30");
        assertThat(r.total()).isEqualByComparingTo("970");
    }

    @Test
    void missingDurationDoesNotQualifyForVisitButKeepsApprovedCheckin() {
        var m = YearMonth.of(2026, 9);
        var visits = new ArrayList<>(perfect(m));
        visits.removeIf(v -> v.date().equals(m.atDay(1)) && "C2".equals(v.customer()));
        visits.add(new SalesVisitEvidence("UNKNOWN", m.atDay(1), "C8", "APPROVED", null, false));
        var r =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 1), null, true, visits, n("5000"));
        assertThat(r.total()).isEqualByComparingTo("970");
        assertThat(r.issues()).isEmpty();
        assertThat(r.days().getFirst().checkins()).isEqualTo(7);
        assertThat(r.days().getFirst().effectiveVisits()).isEqualTo(2);
        assertThat(r.days().getFirst().deduction()).isEqualByComparingTo("30");
    }

    @Test
    void unlinkedSourceRemainsUnknownInsteadOfBeingDeductedAsZeroCompletion() {
        var m = YearMonth.of(2026, 9);
        var unlinked =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 1), null, false, List.of(), n("5000"));
        assertThat(unlinked.total()).isNull();
        assertThat(unlinked.days().getFirst().deduction()).isNull();
        assertThat(unlinked.issues()).containsExactly("销售打卡账号未关联员工，过程考核待核对");
    }

    @Test
    void pendingAndFlaggedRecordsAreAutomaticallyExcludedAndDeductionIsCapped() {
        var m = YearMonth.of(2026, 9);
        var visits =
                List.of(
                        new SalesVisitEvidence(
                                "pending", m.atDay(1), "C1", "PENDING", 600000L, true),
                        new SalesVisitEvidence(
                                "flagged", m.atDay(1), "C2", "FLAGGED", 600000L, true),
                        new SalesVisitEvidence(
                                "valid", m.atDay(1), "C3", "APPROVED", 600000L, true));
        var result =
                SalesPerformanceCalculator.calculate(
                        policy(),
                        m,
                        LocalDate.of(2026, 8, 1),
                        null,
                        true,
                        visits,
                        n("5000"),
                        java.util.Set.of(m.atDay(1), m.atDay(2)),
                        n("1"));
        assertThat(result.issues()).isEmpty();
        assertThat(result.days().getFirst().checkins()).isEqualTo(1);
        assertThat(result.days().getFirst().effectiveVisits()).isEqualTo(1);
        assertThat(result.days().getFirst().deduction()).isEqualByComparingTo("180");
        assertThat(result.days().getLast().deduction()).isEqualByComparingTo("230");
        assertThat(result.processDeduction()).isEqualByComparingTo("400");
        assertThat(result.collectionPay()).isEqualByComparingTo("600");
        assertThat(result.total()).isEqualByComparingTo("600");
    }

    @Test
    void negativeCollectionsDoNotProduceNegativePerformance() {
        var m = YearMonth.of(2026, 9);
        var r =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 1), null, true, perfect(m), n("-100"));
        assertThat(r.collectionPay()).isEqualByComparingTo("0");
        assertThat(r.total()).isEqualByComparingTo("400");
    }

    @org.junit.jupiter.api.Test
    void configuredFirstMonthCanAssessAndSupportsFractionalWeights() {
        var policy =
                new SalesPerformancePolicy(
                        new java.math.BigDecimal("1000"),
                        new java.math.BigDecimal("1000"),
                        "ASSESS",
                        40,
                        7,
                        new java.math.BigDecimal("20"),
                        3,
                        600,
                        new java.math.BigDecimal("30"),
                        new java.math.BigDecimal("1000"),
                        new java.math.BigDecimal("1000"));
        var result =
                SalesPerformanceCalculator.calculate(
                        policy,
                        java.time.YearMonth.of(2026, 9),
                        java.time.LocalDate.of(2026, 9, 1),
                        null,
                        true,
                        java.util.List.of(),
                        new java.math.BigDecimal("500"),
                        java.util.Set.of(),
                        java.math.BigDecimal.ONE,
                        new java.math.BigDecimal("40.5"));
        org.assertj.core.api.Assertions.assertThat(result.processBudget())
                .isEqualByComparingTo("405");
        org.assertj.core.api.Assertions.assertThat(result.collectionPay())
                .isEqualByComparingTo("297.50");
        org.assertj.core.api.Assertions.assertThat(result.total()).isEqualByComparingTo("702.50");
    }

    @Test
    void zeroProcessWeightDoesNotRequireSalesCheckinEvidence() {
        var month = YearMonth.of(2026, 9);
        var result =
                SalesPerformanceCalculator.calculate(
                        policy(),
                        month,
                        LocalDate.of(2026, 8, 1),
                        null,
                        false,
                        List.of(),
                        n("5000"),
                        java.util.Set.of(month.atDay(1)),
                        n("1"),
                        n("0"),
                        "RECORD");
        assertThat(result.issues()).isEmpty();
        assertThat(result.processPay()).isZero();
        assertThat(result.total()).isEqualByComparingTo("1000");
    }

    @Test
    void extraUnreviewedVisitsDoNotBlockAlreadySatisfiedDailyTargets() {
        var month = YearMonth.of(2026, 9);
        var visits = new ArrayList<>(perfect(month));
        visits.add(new SalesVisitEvidence("pending", month.atDay(1), "C8", "PENDING", null, false));
        var result =
                SalesPerformanceCalculator.calculate(
                        policy(), month, LocalDate.of(2026, 8, 1), null, true, visits, n("5000"));
        assertThat(result.issues()).isEmpty();
        assertThat(result.total()).isEqualByComparingTo("1000");
    }
}
