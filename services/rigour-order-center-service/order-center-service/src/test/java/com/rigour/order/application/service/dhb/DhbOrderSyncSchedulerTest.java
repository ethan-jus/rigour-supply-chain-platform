package com.rigour.order.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncMode;
import com.rigour.order.api.v1.model.DhbOrderSyncResult;
import com.rigour.order.application.port.out.DhbOrderSyncCheckpointStore;
import com.rigour.order.application.port.out.DhbSyncTargetDiscoveryClient;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DhbOrderSyncSchedulerTest {
    private static final String TENANT_ID = "019fb000-0000-7000-8000-000000000002";
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb000-0000-7000-8000-000000000010");
    private static final UUID TASK_ID = UUID.fromString("019fb000-0000-7000-8000-000000000020");
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @Test
    void firstRunIsFullAndLaterRunUsesFiveMinuteOverlapAfterSuccessfulImport() {
        DhbOrderSyncService syncService = mock(DhbOrderSyncService.class);
        DhbOrderSyncCheckpointStore checkpointStore = mock(DhbOrderSyncCheckpointStore.class);
        DhbSyncTargetDiscoveryClient targetDiscoveryClient = mock(DhbSyncTargetDiscoveryClient.class);
        DhbOrderSyncScheduleProperties properties = properties();
        DhbOrderSyncScheduler scheduler = new DhbOrderSyncScheduler(syncService, checkpointStore,
                targetDiscoveryClient, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID runId = UUID.fromString("019fb000-0000-7000-8000-000000000011");
        when(targetDiscoveryClient.discover(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new SyncTargetView(TASK_ID, UUID.fromString(TENANT_ID), CONNECTOR_ID)));
        when(syncService.runScheduled(org.mockito.ArgumentMatchers.any(), eq(CONNECTOR_ID),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(success(runId));

        when(checkpointStore.lastSuccessAt(TENANT_ID, CONNECTOR_ID, "ORDER")).thenReturn(null)
                .thenReturn(Instant.parse("2026-08-05T09:30:00Z"));

        scheduler.synchronize();
        scheduler.synchronize();

        ArgumentCaptor<DhbOrderSyncCommand> commands = ArgumentCaptor.forClass(DhbOrderSyncCommand.class);
        verify(syncService, org.mockito.Mockito.times(2)).runScheduled(
                org.mockito.ArgumentMatchers.any(), eq(CONNECTOR_ID), commands.capture());
        assertThat(commands.getAllValues().get(0).updatedFrom()).isNull();
        assertThat(commands.getAllValues().get(0).updatedTo()).isNull();
        assertThat(commands.getAllValues().get(0).mode()).isEqualTo(DhbOrderSyncMode.FULL);
        assertThat(commands.getAllValues().get(1).updatedFrom())
                .isEqualTo(Instant.parse("2026-08-05T09:25:00Z"));
        assertThat(commands.getAllValues().get(1).updatedTo()).isEqualTo(NOW);
        assertThat(commands.getAllValues().get(1).mode()).isEqualTo(DhbOrderSyncMode.INCREMENTAL);
        verify(checkpointStore, org.mockito.Mockito.times(2))
                .markSucceeded(TENANT_ID, CONNECTOR_ID, "ORDER", runId, NOW);

        ArgumentCaptor<CallerIdentity> callers = ArgumentCaptor.forClass(CallerIdentity.class);
        verify(syncService, org.mockito.Mockito.times(2)).runScheduled(callers.capture(),
                eq(CONNECTOR_ID), org.mockito.ArgumentMatchers.any());
        assertThat(callers.getAllValues()).allSatisfy(caller -> {
            assertThat(caller.principalScope()).isEqualTo("SERVICE");
            assertThat(caller.principalId()).isEqualTo(SERVICE_PRINCIPAL_ID);
            assertThat(caller.tenantId()).isEqualTo(UUID.fromString(TENANT_ID));
            assertThat(caller.userId()).isNull();
        });
    }

    @Test
    void emptyDynamicTargetListDoesNotCallSyncService() {
        DhbOrderSyncService syncService = mock(DhbOrderSyncService.class);
        DhbOrderSyncCheckpointStore checkpointStore = mock(DhbOrderSyncCheckpointStore.class);
        DhbSyncTargetDiscoveryClient targetDiscoveryClient = mock(DhbSyncTargetDiscoveryClient.class);
        when(targetDiscoveryClient.discover(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        DhbOrderSyncScheduler scheduler = new DhbOrderSyncScheduler(syncService, checkpointStore,
                targetDiscoveryClient, properties(), Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.synchronize();

        verify(syncService, org.mockito.Mockito.never()).runScheduled(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedImportRecordsFailureWithoutAdvancingCheckpoint() {
        DhbOrderSyncService syncService = mock(DhbOrderSyncService.class);
        DhbOrderSyncCheckpointStore checkpointStore = mock(DhbOrderSyncCheckpointStore.class);
        DhbSyncTargetDiscoveryClient targetDiscoveryClient = mock(DhbSyncTargetDiscoveryClient.class);
        when(targetDiscoveryClient.discover(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new SyncTargetView(TASK_ID, UUID.fromString(TENANT_ID), CONNECTOR_ID)));
        when(syncService.runScheduled(org.mockito.ArgumentMatchers.any(), eq(CONNECTOR_ID),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        DhbOrderSyncScheduler scheduler = new DhbOrderSyncScheduler(syncService, checkpointStore,
                targetDiscoveryClient, properties(), Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.synchronize();

        verify(checkpointStore).markFailed(TENANT_ID, CONNECTOR_ID, "ORDER", null,
                "provider unavailable");
        verify(checkpointStore, org.mockito.Mockito.never()).markSucceeded(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void connectorConflictIsSkippedWithoutWritingFailureCheckpoint() {
        DhbOrderSyncService syncService = mock(DhbOrderSyncService.class);
        DhbOrderSyncCheckpointStore checkpointStore = mock(DhbOrderSyncCheckpointStore.class);
        DhbSyncTargetDiscoveryClient targetDiscoveryClient = mock(DhbSyncTargetDiscoveryClient.class);
        when(targetDiscoveryClient.discover(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new SyncTargetView(TASK_ID, UUID.fromString(TENANT_ID), CONNECTOR_ID)));
        when(syncService.runScheduled(org.mockito.ArgumentMatchers.any(), eq(CONNECTOR_ID),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                        "connector busy", List.of()));
        DhbOrderSyncScheduler scheduler = new DhbOrderSyncScheduler(syncService, checkpointStore,
                targetDiscoveryClient, properties(), Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.synchronize();

        verify(checkpointStore, org.mockito.Mockito.never()).markFailed(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(checkpointStore, org.mockito.Mockito.never()).markSucceeded(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static DhbOrderSyncScheduleProperties properties() {
        DhbOrderSyncScheduleProperties properties = new DhbOrderSyncScheduleProperties();
        properties.setEnabled(true);
        properties.setMaxPages(100);
        properties.setOverlapMinutes(5);
        return properties;
    }

    private static DhbOrderSyncResult success(UUID runId) {
        return new DhbOrderSyncResult(runId, "ORDER", "SUCCEEDED", 1, 1,
                1, 0, 0, 0,
                Set.of("ORDER", "SHIPMENT", "SHIPMENT_LOGISTICS", "RETURN", "RECEIPT", "PAYMENT"));
    }
}
