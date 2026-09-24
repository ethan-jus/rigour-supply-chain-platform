package com.rigour.analytics.application.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshRunView;
import com.rigour.analytics.application.port.out.BiScheduleCoordinator;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class BiScheduledRefreshWorkerTest {
    private final BiScheduleCoordinator coordinator = mock(BiScheduleCoordinator.class);
    private final SupplyDashboardRefreshService refresh = mock(SupplyDashboardRefreshService.class);
    private final UUID tenant = UUID.randomUUID();

    @Test
    void claimFailureDoesNotRefresh() {
        when(coordinator.due()).thenReturn(List.of(new BiScheduleCoordinator.Work(tenant, 1)));
        try (WorkerHandle handle = new WorkerHandle()) {
            handle.worker.consume();
            verifyNoInteractions(refresh);
        }
    }

    @Test
    void lostCompletionRetriesOnlyReceiptNotRefresh() {
        when(coordinator.due())
                .thenReturn(List.of(new BiScheduleCoordinator.Work(tenant, 1)), List.of());
        when(coordinator.claim(eq(tenant), eq(1L), any())).thenReturn(true);
        when(refresh.refreshConfiguredTenant(tenant.toString()))
                .thenReturn(
                        new SupplyDashboardRefreshRunView(
                                1L,
                                "HOURLY",
                                tenant.toString(),
                                "SUCCESS",
                                Instant.now(),
                                Instant.now(),
                                null,
                                1L,
                                1L,
                                0L,
                                null));
        doThrow(new IllegalStateException("network"))
                .doNothing()
                .when(coordinator)
                .complete(eq(tenant), any(), eq("SUCCESS"), isNull());
        try (WorkerHandle handle = new WorkerHandle()) {
            handle.worker.consume();
            handle.worker.consume();
            verify(refresh, times(1)).refreshConfiguredTenant(tenant.toString());
            var tokens = org.mockito.ArgumentCaptor.forClass(UUID.class);
            verify(coordinator, times(2))
                    .complete(eq(tenant), tokens.capture(), eq("SUCCESS"), isNull());
            org.assertj.core.api.Assertions.assertThat(tokens.getAllValues().get(0))
                    .isEqualTo(tokens.getAllValues().get(1));
        }
    }

    @Test
    void executionFailureReportsFailed() {
        when(coordinator.due()).thenReturn(List.of(new BiScheduleCoordinator.Work(tenant, 1)));
        when(coordinator.claim(eq(tenant), eq(1L), any())).thenReturn(true);
        when(refresh.refreshConfiguredTenant(anyString()))
                .thenThrow(new IllegalStateException("test"));
        try (WorkerHandle handle = new WorkerHandle()) {
            handle.worker.consume();
            verify(coordinator).complete(eq(tenant), any(), eq("FAILED"), anyString());
        }
    }

    private class WorkerHandle implements AutoCloseable {
        final BiScheduledRefreshWorker worker = new BiScheduledRefreshWorker(coordinator, refresh);

        public void close() {
            worker.close();
        }
    }
}
