package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository.WorkDaySnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SalesWorkAttendanceServiceStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);

    @Test
    void finishedDayUsesPinnedMinimumWithoutClaimingHrConclusion() {
        assertThat(SalesWorkAttendanceService.attendanceStatus(
                day("FINISHED", 480, Instant.parse("2026-08-08T10:00:00Z")),
                TODAY.minusDays(1), TODAY, 420)).isEqualTo("MEETS_MINIMUM");
        assertThat(SalesWorkAttendanceService.attendanceStatus(
                day("FINISHED", 180, Instant.parse("2026-08-08T10:00:00Z")),
                TODAY.minusDays(1), TODAY, 420)).isEqualTo("SHORT");
    }

    @Test
    void oldActiveDayIsMissingCheckoutRatherThanStillInProgress() {
        assertThat(SalesWorkAttendanceService.attendanceStatus(
                day("ACTIVE", 0, null), TODAY.minusDays(1), TODAY, 420))
                .isEqualTo("MISSING_CHECK_OUT");
        assertThat(SalesWorkAttendanceService.attendanceStatus(
                day("ACTIVE", 0, null), TODAY, TODAY, 420))
                .isEqualTo("IN_PROGRESS");
    }

    private static WorkDaySnapshot day(String status, int minutes, Instant checkedOutAt) {
        return new WorkDaySnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TODAY,
                "Asia/Shanghai", UUID.randomUUID(), status, Instant.parse("2026-08-08T01:00:00Z"),
                checkedOutAt, null, 0, 0, minutes, "PENDING");
    }
}
