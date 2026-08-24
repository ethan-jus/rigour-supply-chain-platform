package com.rigour.erp.application.service.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.application.port.out.DhbSupplyDataClient;
import com.rigour.erp.application.port.out.DhbSupplySyncTargetDiscoveryClient;
import com.rigour.erp.application.port.out.ErpSyncRunAuditStore.ScheduledSkipReason;
import com.rigour.erp.application.port.out.SupplyDataStore;
import com.rigour.erp.application.service.sync.BusinessDictionaryCoverageService;
import com.rigour.erp.application.service.sync.ErpScheduledSyncSkipException;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.integration.client.ConnectorSyncLeaseClient;
import com.rigour.integration.client.ConnectorSyncLeaseClient.LeaseGuard;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** ERP 供应链定时同步在租约与本地锁边界的语义测试。 */
class SupplyDataSyncServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb100-0000-7000-8000-000000000011");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb100-0000-7000-8000-000000000012");
    private static final UUID RUN_ID = UUID.fromString("019fb100-0000-7000-8000-000000000013");

    @Test
    void scheduledAcquireConflictBecomesTypedSkipBeforeActionStarts() {
        SupplyDataStore store = mock(SupplyDataStore.class);
        ConnectorSyncLeaseClient lease = mock(ConnectorSyncLeaseClient.class);
        when(lease.executeWithLeaseGuard(any(), any(), any()))
                .thenThrow(alreadyRunning("connector busy"));
        SupplyDataSyncService service = service(store, mock(DhbSupplyDataClient.class), lease);

        assertThatThrownBy(() -> service.runScheduled(scheduledCaller(), CONNECTOR_ID,
                SupplyDataObjectType.SUPPLIER, 3))
                .isInstanceOfSatisfying(ErpScheduledSyncSkipException.class, skip -> {
                    assertThat(skip.blockedObjectType()).isEqualTo("SUPPLIER");
                    assertThat(skip.reason()).isEqualTo(ScheduledSkipReason.CONNECTOR_LEASE_CONFLICT);
                });
        verify(store, never()).startScheduledRun(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void scheduledLocalObjectLockConflictBecomesTypedSkipBeforeRunCreation() {
        SupplyDataStore store = mock(SupplyDataStore.class);
        when(store.startScheduledRun(TENANT_ID.toString(), CONNECTOR_ID, null,
                SupplyDataObjectType.WAREHOUSE, 3)).thenThrow(alreadyRunning("object lock busy"));
        SupplyDataSyncService service = service(
                store, mock(DhbSupplyDataClient.class), passthroughLease());

        assertThatThrownBy(() -> service.runScheduled(scheduledCaller(), CONNECTOR_ID,
                SupplyDataObjectType.WAREHOUSE, 3))
                .isInstanceOfSatisfying(ErpScheduledSyncSkipException.class, skip -> {
                    assertThat(skip.blockedObjectType()).isEqualTo("WAREHOUSE");
                    assertThat(skip.reason()).isEqualTo(ScheduledSkipReason.OBJECT_SYNC_LOCK_CONFLICT);
                });
        verify(store, never()).failRun(any(), any(), any(), any());
    }

    @Test
    void leaseGuardFailureAfterActionMarksCreatedRunFailedAndPreventsSuccess() {
        SupplyDataStore store = mock(SupplyDataStore.class);
        DhbSupplyDataClient client = mock(DhbSupplyDataClient.class);
        LeaseGuard guard = mock(LeaseGuard.class);
        BusinessException leaseLost = alreadyRunning("lease lost after action");
        doThrow(leaseLost).when(guard).ensureActive();
        when(store.startScheduledRun(TENANT_ID.toString(), CONNECTOR_ID, null,
                SupplyDataObjectType.PURCHASE_ORDER, 3)).thenReturn(RUN_ID);
        when(client.collect(any(), eq(CONNECTOR_ID), eq(SupplyDataObjectType.PURCHASE_ORDER),
                eq(3), eq(List.of()))).thenReturn(emptyCollected(SupplyDataObjectType.PURCHASE_ORDER));
        SupplyDataSyncService service = service(store, client, passthroughLease(guard));

        assertThatThrownBy(() -> service.runScheduled(scheduledCaller(), CONNECTOR_ID,
                SupplyDataObjectType.PURCHASE_ORDER, 3)).isSameAs(leaseLost);
        verify(store).failRun(eq(TENANT_ID.toString()), eq(RUN_ID), any(), same(leaseLost));
        verify(store, never()).completeRunWithSourcePresence(any(), any(), any(), any());
    }

    @Test
    void successfulRunFencesReconciliationAndCompletionBeforeLeaseReturns() {
        SupplyDataStore store = mock(SupplyDataStore.class);
        DhbSupplyDataClient client = mock(DhbSupplyDataClient.class);
        LeaseGuard guard = mock(LeaseGuard.class);
        when(store.startScheduledRun(TENANT_ID.toString(), CONNECTOR_ID, null,
                SupplyDataObjectType.SUPPLIER, 3)).thenReturn(RUN_ID);
        when(client.collect(any(), eq(CONNECTOR_ID), eq(SupplyDataObjectType.SUPPLIER),
                eq(3), eq(List.of()))).thenReturn(emptyCollected(SupplyDataObjectType.SUPPLIER));
        SupplyDataSyncService service = service(store, client, passthroughLease(guard));

        var result = service.runScheduled(scheduledCaller(), CONNECTOR_ID,
                SupplyDataObjectType.SUPPLIER, 3);

        assertThat(result.runId()).isEqualTo(RUN_ID);
        InOrder successBoundary = inOrder(guard, store);
        successBoundary.verify(guard).ensureActive();
        successBoundary.verify(store).completeRunWithSourcePresence(eq(TENANT_ID.toString()),
                eq(RUN_ID), any(), any());
    }

    private static SupplyDataSyncService service(SupplyDataStore store, DhbSupplyDataClient client,
                                                 ConnectorSyncLeaseClient lease) {
        return new SupplyDataSyncService(client, mock(DhbSupplySyncTargetDiscoveryClient.class),
                store, mock(BusinessDictionaryCoverageService.class), lease);
    }

    private static CallerIdentity scheduledCaller() {
        return new CallerIdentity("SERVICE", UUID.randomUUID(), TENANT_ID,
                null, null, UUID.randomUUID(), 0, 0, 0, Set.of("ERP_SYNC_SERVICE"),
                Set.of("integration:dhb:read"));
    }

    private static ConnectorSyncLeaseClient passthroughLease() {
        return passthroughLease(mock(LeaseGuard.class));
    }

    private static ConnectorSyncLeaseClient passthroughLease(LeaseGuard guard) {
        ConnectorSyncLeaseClient lease = mock(ConnectorSyncLeaseClient.class);
        when(lease.executeWithLeaseGuard(any(), any(), any())).thenAnswer(invocation -> {
            Function<LeaseGuard, ?> action = invocation.getArgument(2);
            return action.apply(guard);
        });
        return lease;
    }

    private static BusinessException alreadyRunning(String message) {
        return new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING, message, List.of());
    }

    private static DhbSupplyDataClient.Collected emptyCollected(SupplyDataObjectType type) {
        return new DhbSupplyDataClient.Collected(type, 0, 1, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }
}
