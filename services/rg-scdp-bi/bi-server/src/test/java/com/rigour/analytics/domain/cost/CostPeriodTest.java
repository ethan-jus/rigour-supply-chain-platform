package com.rigour.analytics.domain.cost;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class CostPeriodTest {
    @Test
    void clipsShortMonthAndLeapDay() {
        var p = CostPeriod.resolve(2024, 3, "previous", LocalDate.of(2024, 3, 31));
        assertThat(p.previousTo()).isEqualTo("2024-02-29");
        assertThat(CostPeriod.resolve(2024, 2, "yoy", LocalDate.of(2024, 2, 29)).previousTo())
                .isEqualTo("2023-02-28");
    }

    @Test
    void historyIsFullMonthAndJanuaryCrossesYear() {
        var p = CostPeriod.resolve(2026, 9, "previous", LocalDate.of(2026, 10, 16));
        assertThat(p.to()).isEqualTo("2026-09-30");
        assertThat(p.previousTo()).isEqualTo("2026-08-31");
        assertThat(
                        CostPeriod.resolve(2026, 1, "previous", LocalDate.of(2026, 10, 16))
                                .previousFrom())
                .isEqualTo("2025-12-01");
    }

    @Test
    void yearIsAlwaysYoyAndFutureIsRejected() {
        var p = CostPeriod.resolve(2026, 0, "previous", LocalDate.of(2026, 10, 16));
        assertThat(p.compare()).isEqualTo("yoy");
        assertThat(p.previousTo()).isEqualTo("2025-10-16");
        assertThatThrownBy(
                        () -> CostPeriod.resolve(2026, 11, "previous", LocalDate.of(2026, 10, 16)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
