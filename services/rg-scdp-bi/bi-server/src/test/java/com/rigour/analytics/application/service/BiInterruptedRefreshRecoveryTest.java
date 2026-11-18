package com.rigour.analytics.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.application.port.out.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class BiInterruptedRefreshRecoveryTest {
    private final SupplyDashboardStore store = mock(SupplyDashboardStore.class);
    private final Instant now = Instant.parse("2026-11-18T02:00:00Z");
    private final SupplyDashboardRefreshService service = new SupplyDashboardRefreshService(
            store, mock(EmployeeAnalyticsStore.class), mock(CityContactAnalyticsStore.class),
            Clock.fixed(now, ZoneOffset.UTC), true, Duration.ofHours(2), Duration.ofMinutes(55));

    @Test
    void recentOrMissingStartDoesNotTouchRefreshLock() {
        assertThat(service.recoverInterruptedSchedule("tenant", null)).isFalse();
        assertThat(service.recoverInterruptedSchedule("tenant", now.minusSeconds(56 * 60))).isFalse();
        verifyNoInteractions(store);
    }

    @Test
    void stillHeldLockDoesNotReleaseOriginalExecution() {
        assertThat(service.recoverInterruptedSchedule("tenant", now.minusSeconds(7200))).isFalse();
        verify(store, never()).releaseRefreshLock(any(), any(), any(), any(), any());
    }

    @Test
    void expiredExecutionIsRecordedAsFailureNotSuccessAndNeverRefreshesBusinessData() {
        when(store.acquireRefreshLock(eq("tenant"), anyString(), eq(now), any())).thenReturn(true);
        assertThat(service.recoverInterruptedSchedule("tenant", now.minusSeconds(7200))).isTrue();
        verify(store).releaseRefreshLock(eq("tenant"), anyString(), eq("FAILED"), eq(now), contains("中断"));
        verify(store, never()).synchronizeSourceSnapshots(any());
    }
}
