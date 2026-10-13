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
    private SalesPerformanceCalculator.Policy policy() {
        return new SalesPerformanceCalculator.Policy(
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

    private List<SalesPerformanceCalculator.Visit> perfect(YearMonth m) {
        List<SalesPerformanceCalculator.Visit> result = new ArrayList<>();
        for (int d = 1; d <= m.lengthOfMonth(); d++)
            for (int c = 0; c < 7; c++)
                result.add(
                        new SalesPerformanceCalculator.Visit(
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
                    new SalesPerformanceCalculator.Visit(
                            "S" + c,
                            m.atDay(1),
                            "C" + c,
                            "APPROVED",
                            c == 0 ? 600000L : c == 1 ? 599999L : c == 2 ? 600001L : 0L,
                            true));
        evidence.add(
                new SalesPerformanceCalculator.Visit(
                        "DUP", m.atDay(1), "C0", "APPROVED", 600000L, true));
        var r =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 1), null, true, evidence, n("5000"));
        assertThat(r.days().getFirst().checkins()).isEqualTo(7);
        assertThat(r.days().getFirst().effectiveVisits()).isEqualTo(2);
        assertThat(r.processDeduction()).isEqualByComparingTo("30");
        assertThat(r.total()).isEqualByComparingTo("970");
    }

    @Test
    void missingDurationAndUnlinkedEmployeeRequireReviewInsteadOfAutomaticPenalty() {
        var m = YearMonth.of(2026, 9);
        var visits = new ArrayList<>(perfect(m));
        visits.add(
                new SalesPerformanceCalculator.Visit(
                        "UNKNOWN", m.atDay(1), "C8", "APPROVED", null, false));
        var r =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 1), null, true, visits, n("5000"));
        assertThat(r.total()).isNull();
        assertThat(r.issues()).anyMatch(x -> x.contains("可信沟通时长"));
        var unlinked =
                SalesPerformanceCalculator.calculate(
                        policy(), m, LocalDate.of(2026, 8, 1), null, false, List.of(), n("5000"));
        assertThat(unlinked.total()).isNull();
        assertThat(unlinked.days().getFirst().deduction()).isNull();
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
}
