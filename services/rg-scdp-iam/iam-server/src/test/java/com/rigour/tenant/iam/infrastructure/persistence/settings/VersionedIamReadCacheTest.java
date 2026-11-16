package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VersionedIamReadCacheTest {
    static final class TestClock extends Clock {
        long millis = 1000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    @Test void matchingVersionHitsAndChangedVersionCannotReadTheOldValue() {
        var cache = new VersionedIamReadCache<String, String>();
        var calls = new AtomicInteger();
        assertThat(cache.get("tenant:user", 1, () -> { calls.incrementAndGet(); return "read,write"; })).isEqualTo("read,write");
        assertThat(cache.get("tenant:user", 1, () -> { throw new AssertionError(); })).isEqualTo("read,write");
        assertThat(cache.get("tenant:user", 2, () -> { calls.incrementAndGet(); return "read"; })).isEqualTo("read");
        assertThat(cache.get("other:user", 2, () -> "none")).isEqualTo("none");
        assertThat(calls).hasValue(2);
    }

    @Test void expiryClockRollbackAndCapacityBoundAllForceReloads() {
        var clock = new TestClock();
        var cache = new VersionedIamReadCache<String, String>(2, Duration.ofSeconds(1), clock);
        cache.get("one", 1, () -> "old");
        clock.millis += 1000;
        assertThat(cache.get("one", 1, () -> "new")).isEqualTo("new");
        clock.millis -= 1;
        assertThat(cache.get("one", 1, () -> "rollback")).isEqualTo("rollback");
        cache.get("two", 1, () -> "two"); cache.get("three", 1, () -> "three");
        assertThat(cache.get("one", 1, () -> "evicted")).isEqualTo("evicted");
    }

    @Test void failedLoadsNeverBecomeCachedAuthorization() {
        var cache = new VersionedIamReadCache<String, String>();
        assertThatThrownBy(() -> cache.get("user", 1, () -> { throw new IllegalStateException(); })).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get("user", 1, () -> "verified")).isEqualTo("verified");
    }
}
