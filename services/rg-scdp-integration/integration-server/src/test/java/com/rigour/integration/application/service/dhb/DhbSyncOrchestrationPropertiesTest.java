package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DhbSyncOrchestrationPropertiesTest {

    @Test
    void scheduledWindowFromAcceptsBusinessDateAndIsoTime() {
        assertThat(DhbSyncOrchestrationProperties.parseWindowStart("2026-09-01"))
                .isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        assertThat(DhbSyncOrchestrationProperties.parseWindowStart("2026-09-01T00:00:00"))
                .isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        assertThat(DhbSyncOrchestrationProperties.parseWindowStart("2026-09-01T00:00:00+08:00"))
                .isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
    }

    @Test
    void validateRequiresScheduledWindowFromWhenSchedulerIsEnabled() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduled-window-from不能为空");
    }

    @Test
    void incrementalWindowFromDefaultsToOrderCutoverAndCanBeOverridden() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();

        assertThat(properties.incrementalWindowFromInstant())
                .isEqualTo(Instant.parse("2026-09-03T16:00:00Z"));

        properties.setIncrementalWindowFrom("2026-09-10");
        assertThat(properties.incrementalWindowFromInstant())
                .isEqualTo(Instant.parse("2026-09-09T16:00:00Z"));

        properties.setIncrementalWindowFrom("  ");
        assertThat(properties.incrementalWindowFromInstant())
                .isEqualTo(Instant.parse("2026-09-03T16:00:00Z"));
    }

    @Test
    void validateAllowsButtonIncrementalSyncWithoutScheduledWindowFrom() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void validateRejectsIncrementalWindowOutsideOneHourToNinetyDays() {
        DhbSyncOrchestrationProperties tooShort = new DhbSyncOrchestrationProperties();
        tooShort.setIncrementalWindow(Duration.ofMinutes(30));
        assertThatThrownBy(tooShort::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("增量窗口");

        DhbSyncOrchestrationProperties tooLong = new DhbSyncOrchestrationProperties();
        tooLong.setIncrementalWindow(Duration.ofDays(91));
        assertThatThrownBy(tooLong::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("增量窗口");
    }

    @Test
    void effectiveIncrementalWindowFallsBackToDefaultAndCapsAtNinetyDays() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        assertThat(properties.effectiveIncrementalWindow()).isEqualTo(Duration.ofDays(7));

        properties.setIncrementalWindow(null);
        assertThat(properties.effectiveIncrementalWindow()).isEqualTo(Duration.ofDays(7));
        properties.setIncrementalWindow(Duration.ZERO);
        assertThat(properties.effectiveIncrementalWindow()).isEqualTo(Duration.ofDays(7));
        properties.setIncrementalWindow(Duration.ofMinutes(-5));
        assertThat(properties.effectiveIncrementalWindow()).isEqualTo(Duration.ofDays(7));

        properties.setIncrementalWindow(Duration.ofDays(2));
        assertThat(properties.effectiveIncrementalWindow()).isEqualTo(Duration.ofDays(2));
        properties.setIncrementalWindow(Duration.ofDays(365));
        assertThat(properties.effectiveIncrementalWindow()).isEqualTo(Duration.ofDays(90));
    }
}
