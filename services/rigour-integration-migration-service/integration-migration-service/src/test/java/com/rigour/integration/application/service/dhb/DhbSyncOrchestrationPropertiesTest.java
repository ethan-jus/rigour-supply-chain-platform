package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
