package com.rigour.erp.application.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.api.v1.model.ErpDataSyncCommand;
import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.port.out.DhbProductSyncTargetDiscoveryClient;
import com.rigour.erp.application.port.out.DhbSupplySyncTargetDiscoveryClient;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ErpDataSyncSchedulerTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb200-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb200-0000-7000-8000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("019fb200-0000-7000-8000-000000000003");

    @Test
    void discoversTargetsAndInvokesTheUnifiedSyncEntryInDependencyOrder() {
        ErpDataSyncService syncService = mock(ErpDataSyncService.class);
        DhbProductSyncTargetDiscoveryClient productDiscovery = mock(DhbProductSyncTargetDiscoveryClient.class);
        DhbSupplySyncTargetDiscoveryClient supplyDiscovery = mock(DhbSupplySyncTargetDiscoveryClient.class);
        ErpDataSyncScheduleProperties properties = enabledProperties();
        SyncTargetView target = new SyncTargetView(TASK_ID, TENANT_ID, CONNECTOR_ID);
        when(productDiscovery.discover(any())).thenReturn(List.of(target));
        when(supplyDiscovery.discover(any())).thenReturn(List.of(target));
        when(syncService.runScheduled(any(), any(), any(ErpDataSyncCommand.class)))
                .thenAnswer(invocation -> result(invocation.getArgument(2)));

        ErpDataSyncScheduler scheduler = new ErpDataSyncScheduler(syncService, productDiscovery,
                supplyDiscovery, properties);
        scheduler.synchronize();

        ArgumentCaptor<ErpDataSyncCommand> commands = ArgumentCaptor.forClass(ErpDataSyncCommand.class);
        verify(syncService, org.mockito.Mockito.times(11)).runScheduled(any(), any(), commands.capture());
        assertThat(commands.getAllValues()).extracting(ErpDataSyncCommand::objectType)
                .containsExactly("CATEGORY", "BRAND", "SPECIFICATION", "TAG", "PRODUCT_SPU",
                        "SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER", "PURCHASE_RETURN",
                        "WAREHOUSING_RECEIPT", "INVENTORY");
        assertThat(commands.getAllValues()).allSatisfy(command -> assertThat(command.maxPages()).isEqualTo(7));

        ArgumentCaptor<CallerIdentity> callers = ArgumentCaptor.forClass(CallerIdentity.class);
        verify(syncService, org.mockito.Mockito.times(11)).runScheduled(callers.capture(),
                org.mockito.ArgumentMatchers.eq(CONNECTOR_ID), any(ErpDataSyncCommand.class));
        assertThat(callers.getAllValues()).allSatisfy(caller -> {
            assertThat(caller.principalScope()).isEqualTo("SERVICE");
            assertThat(caller.tenantId()).isEqualTo(TENANT_ID);
            assertThat(caller.userId()).isNull();
            assertThat(caller.permissions()).contains("integration:dhb:read");
        });
    }

    @Test
    void discoveryFailureSkipsTheWholeBatch() {
        ErpDataSyncService syncService = mock(ErpDataSyncService.class);
        DhbProductSyncTargetDiscoveryClient productDiscovery = mock(DhbProductSyncTargetDiscoveryClient.class);
        DhbSupplySyncTargetDiscoveryClient supplyDiscovery = mock(DhbSupplySyncTargetDiscoveryClient.class);
        when(productDiscovery.discover(any())).thenThrow(new IllegalStateException("integration unavailable"));
        ErpDataSyncScheduler scheduler = new ErpDataSyncScheduler(syncService, productDiscovery,
                supplyDiscovery, enabledProperties());

        scheduler.synchronize();

        verify(supplyDiscovery, never()).discover(any());
        verify(syncService, never()).runScheduled(any(), any(), any());
    }

    @Test
    void disabledScheduleDoesNotDiscoverTargets() {
        ErpDataSyncService syncService = mock(ErpDataSyncService.class);
        DhbProductSyncTargetDiscoveryClient productDiscovery = mock(DhbProductSyncTargetDiscoveryClient.class);
        DhbSupplySyncTargetDiscoveryClient supplyDiscovery = mock(DhbSupplySyncTargetDiscoveryClient.class);
        ErpDataSyncScheduleProperties properties = new ErpDataSyncScheduleProperties();
        ErpDataSyncScheduler scheduler = new ErpDataSyncScheduler(syncService, productDiscovery,
                supplyDiscovery, properties);

        scheduler.synchronize();

        verify(productDiscovery, never()).discover(any());
        verify(supplyDiscovery, never()).discover(any());
    }

    @Test
    void rejectsUnsafePageLimitBeforeCallingIntegration() {
        ErpDataSyncService syncService = mock(ErpDataSyncService.class);
        DhbProductSyncTargetDiscoveryClient productDiscovery = mock(DhbProductSyncTargetDiscoveryClient.class);
        DhbSupplySyncTargetDiscoveryClient supplyDiscovery = mock(DhbSupplySyncTargetDiscoveryClient.class);
        ErpDataSyncScheduleProperties properties = enabledProperties();
        properties.setMaxPages(101);
        ErpDataSyncScheduler scheduler = new ErpDataSyncScheduler(syncService, productDiscovery,
                supplyDiscovery, properties);

        assertThatThrownBy(scheduler::synchronize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1到100");
        verify(productDiscovery, never()).discover(any());
    }

    @Test
    void skipsTenantWithMultipleActiveConnectorsToAvoidCrossConnectorOverwrite() {
        ErpDataSyncService syncService = mock(ErpDataSyncService.class);
        DhbProductSyncTargetDiscoveryClient productDiscovery = mock(DhbProductSyncTargetDiscoveryClient.class);
        DhbSupplySyncTargetDiscoveryClient supplyDiscovery = mock(DhbSupplySyncTargetDiscoveryClient.class);
        SyncTargetView first = new SyncTargetView(TASK_ID, TENANT_ID, CONNECTOR_ID);
        SyncTargetView second = new SyncTargetView(UUID.fromString("019fb200-0000-7000-8000-000000000004"),
                TENANT_ID, UUID.fromString("019fb200-0000-7000-8000-000000000005"));
        when(productDiscovery.discover(any())).thenReturn(List.of(first, second));
        when(supplyDiscovery.discover(any())).thenReturn(List.of());
        ErpDataSyncScheduler scheduler = new ErpDataSyncScheduler(syncService, productDiscovery,
                supplyDiscovery, enabledProperties());

        scheduler.synchronize();

        verify(syncService, never()).runScheduled(any(), any(), any());
    }

    private static ErpDataSyncScheduleProperties enabledProperties() {
        ErpDataSyncScheduleProperties properties = new ErpDataSyncScheduleProperties();
        properties.setEnabled(true);
        properties.setMaxPages(7);
        return properties;
    }

    private static ErpDataSyncResult result(ErpDataSyncCommand command) {
        return new ErpDataSyncResult(UUID.randomUUID(), command.objectType(), "SUCCEEDED", CONNECTOR_ID,
                1, 1, 0, 0, 0, 1, Instant.parse("2026-08-12T03:00:00Z"));
    }
}
