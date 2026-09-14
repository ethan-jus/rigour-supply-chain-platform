package com.rigour.analytics.application.service;

import com.rigour.analytics.application.model.BiBusinessTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 北京时间月界与 Instant 表示兼容；不重写历史源日期或 UTC 存储。 */
class BiBusinessTimeTest {
    @Test void monthStartsAtShanghaiMidnightBeforeUtcMonthBoundary() {
        assertThat(BiBusinessTime.monthStart(Instant.parse("2026-08-31T15:59:59.999999Z")))
                .isEqualTo(Instant.parse("2026-07-31T16:00:00Z"));
        assertThat(BiBusinessTime.monthStart(Instant.parse("2026-08-31T16:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        assertThat(BiBusinessTime.monthStart(Instant.parse("2026-12-31T16:00:00Z")))
                .isEqualTo(Instant.parse("2026-12-31T16:00:00Z"));
    }

    @Test void explicitOffsetAndUtcInstantAreTheSameMoment() {
        var offset = OffsetDateTime.parse("2026-09-01T00:00:00+08:00").toInstant();
        assertThat(BiBusinessTime.monthStart(offset)).isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        // A legacy date-only order encoded as UTC midnight still belongs to September 1.
        assertThat(Instant.parse("2026-09-01T00:00:00Z").atZone(BiBusinessTime.ZONE).toLocalDate())
                .hasToString("2026-09-01");
    }
}
