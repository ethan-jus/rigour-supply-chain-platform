package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.application.port.out.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DhbOrderSyncLifecycleTest {
    private final UUID tenant = UUID.randomUUID(), task = UUID.randomUUID(), run = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-09-30T05:51:05Z"),
            to = Instant.parse("2026-09-30T07:15:05Z");
    private final DhbSyncStore store = mock(DhbSyncStore.class);
    private final DhbClient client = mock(DhbClient.class);
    private final DhbOrderSyncService service = new DhbOrderSyncService(store, client,
            mock(OrderSalesOrderProjectionClient.class), mock(HrDhbStaffSyncClient.class));
    private final SyncRunCommand command = new SyncRunCommand(from, to, null, null, null, "RECEIPT");

    @BeforeEach
    void setUp() {
        when(store.loadTask(tenant, task)).thenReturn(new DhbSyncStore.SyncTaskContext(
                tenant, task, UUID.randomUUID(), "orders", "ORDER", "IDLE",
                "https://example.test", "test", "ACTIVE", 100, 1, 0, true));
        when(store.beginRun(eq(tenant), any(), eq(task), eq(from), eq(to)))
                .thenReturn(new DhbSyncStore.SyncRunStarted(run, null));
    }

    @Test
    void initializationFailureTerminatesTheAlreadyStartedRun() {
        var failure = new IllegalStateException("connection closed during recovery query");
        doThrow(failure).when(store).resolveRecoveredProjectionIssues(eq(tenant), any());

        assertThatThrownBy(this::synchronize).isSameAs(failure);

        verifyFailedRun(failure.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void initialLogFailureStillTerminatesRunWhenFailureLogAlsoFails() {
        var failure = new IllegalStateException("log connection closed");
        doThrow(failure).when(store).recordSyncLog(any(), any(), any(), any(), any(), nullable(String.class));

        assertThatThrownBy(this::synchronize).isSameAs(failure);

        verifyFailedRun(failure.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void failureLogFailureDoesNotPreventRunTermination() {
        var failure = new IllegalStateException("source unavailable");
        when(client.getReceipts(any(), any())).thenThrow(failure);
        doThrow(new IllegalStateException("audit unavailable")).when(store)
                .recordSyncLog(eq(tenant), eq(task), eq(run), eq("ERROR"), any(), any());

        assertThatThrownBy(this::synchronize).isSameAs(failure);

        verifyFailedRun(failure.getMessage());
    }

    @Test
    void terminationPersistenceFailureKeepsTheOriginalSyncError() {
        var failure = new IllegalStateException("source unavailable");
        when(client.getReceipts(any(), any())).thenThrow(failure);
        doThrow(new IllegalStateException("database unavailable")).when(store).finishRun(
                any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong(), anyLong(),
                anyLong(), nullable(String.class), nullable(String.class), nullable(String.class));

        assertThatThrownBy(this::synchronize).isSameAs(failure);

        verifyFailedRun(failure.getMessage());
    }

    private void synchronize() {
        service.runOrderPull(DhbSyncOrchestrationService.serviceCaller(tenant), task, command, 100);
    }

    private void verifyFailedRun(String message) {
        verify(store).finishRun(eq(tenant), any(), eq(task), eq(run), eq(from), eq(to), eq("FAILED"),
                eq(0L), eq(0L), eq(0L), eq(0L), isNull(), eq("DHB_SYNC_FAILED"), eq(message));
    }
}
