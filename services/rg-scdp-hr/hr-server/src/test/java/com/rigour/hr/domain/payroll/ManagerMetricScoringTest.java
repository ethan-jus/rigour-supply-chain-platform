package com.rigour.hr.domain.payroll;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class ManagerMetricScoringTest {
    static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    static List<PerformanceBand> bands(String middle) {
        return List.of(
                new PerformanceBand(d("0"), d("1"), true, false, "LINEAR", d("1")),
                new PerformanceBand(d("1"), d("1.5"), true, false, "FIXED", d("1")),
                new PerformanceBand(d("1.5"), d("2"), true, false, "FIXED", d(middle)),
                new PerformanceBand(d("2"), null, true, false, "FIXED", d("1.5")));
    }

    static ManagerPerformanceResult calculate(
            String actual, List<PerformanceBand> bands, String mode, LocalDate protection) {
        return SalarySchemeCalculator.manager(
                d("1000"),
                List.of(
                        new ManagerMetric(
                                "store", "建联门店", d(actual), d("100"), d("10"), bands, null, true,
                                mode),
                        new ManagerMetric(
                                "other", "其他指标", d("100"), d("100"), d("90"), List.of(), null)),
                List.of(),
                null,
                YearMonth.of(2026, 9),
                LocalDate.of(2026, 1, 1),
                null,
                protection,
                false,
                Set.of(),
                "NONE",
                "ACTUAL_COMPLETION");
    }

    @ParameterizedTest
    @CsvSource({
        "0,0",
        "90,0.9",
        "100,1",
        "110,1",
        "149.999,1",
        "150,1.2",
        "199.999,1.2",
        "200,1.5",
        "300,1.5"
    })
    void scoresEachMetricBeforeApplyingItsWeight(String actual, String score) {
        var result = calculate(actual, bands("1.2"), "TIERED", LocalDate.of(2026, 2, 1));
        assertThat(result.issues()).isEmpty();
        var metric = result.metrics().getFirst();
        assertThat(metric.completion()).isEqualByComparingTo(d(actual).movePointLeft(2));
        assertThat(metric.coefficient()).isEqualByComparingTo(score);
        assertThat(metric.contribution()).isEqualByComparingTo(d(score).multiply(d("0.1")));
        assertThat(result.amount())
                .isEqualByComparingTo(d("1000").multiply(d("0.9").add(metric.contribution())));
    }

    @Test
    void coefficientsComeFromHrRulesAndRawCompletionIsKeptSeparately() {
        var result = calculate("150", bands("1.7"), "TIERED", LocalDate.of(2026, 2, 1));
        assertThat(result.weightedCompletion()).isEqualByComparingTo("1.05");
        assertThat(result.coefficient()).isEqualByComparingTo("1.07");
        assertThat(result.amount()).isEqualByComparingTo("1070");
    }

    @Test
    void historicalMissingModeDoesNotActivatePreviouslyUnusedBands() {
        assertThat(calculate("150", bands("1.2"), null, LocalDate.of(2026, 2, 1)).amount())
                .isEqualByComparingTo("1050");
    }

    @Test
    void anAmbiguousOrMissingBandCannotBecomeZeroOrSilentlyUseActualCompletion() {
        var duplicate = new ArrayList<>(bands("1.2"));
        duplicate.add(duplicate.get(2));
        assertThat(calculate("150", duplicate, "TIERED", LocalDate.of(2026, 2, 1)).amount())
                .isNull();
        assertThat(calculate("150", List.of(), "TIERED", LocalDate.of(2026, 2, 1)).issues())
                .anyMatch(s -> s.contains("计分区间"));
    }

    @Test
    void protectionAndScoredDaysRemainWeightedSeparately() {
        var result = calculate("200", bands("1.2"), "TIERED", LocalDate.of(2026, 9, 16));
        assertThat(result.amount()).isEqualByComparingTo("1025");
        assertThat(result.protectedDays()).isEqualTo(15);
    }
}
