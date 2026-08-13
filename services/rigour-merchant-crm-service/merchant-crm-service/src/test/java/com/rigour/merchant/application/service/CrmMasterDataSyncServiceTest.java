package com.rigour.merchant.application.service;

import com.rigour.merchant.application.port.out.CrmMasterDataStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.ImportResult;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.Collected;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.application.port.out.DhbCrmSyncTargetDiscoveryClient;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrmMasterDataSyncServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb600-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb600-0000-7000-8000-000000000002");

    @Test
    void scheduledRunUsesErpCompatibleDependencyOrderAndCompletesEachObject() {
        DhbCrmMasterDataClient client = mock(DhbCrmMasterDataClient.class);
        DhbCrmSyncTargetDiscoveryClient discovery = mock(DhbCrmSyncTargetDiscoveryClient.class);
        CrmMasterDataStore store = mock(CrmMasterDataStore.class);
        CrmMasterDataSyncService service = new CrmMasterDataSyncService(client, discovery, store);
        when(store.startRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(null), any(), eq(10), eq("SCHEDULED")))
                .thenAnswer(invocation -> UUID.randomUUID());
        when(client.collect(any(), eq(CONNECTOR_ID), any(), eq(10)))
                .thenAnswer(invocation -> {
                    CrmMasterDataObjectType type = invocation.getArgument(2);
                    SourceRecord item = new SourceRecord(type.name() + "-1", null,
                            type.name(), null, null, null, Map.of("type", type.name()));
                    return new Collected(type, 1, 1, List.of(item));
                });
        when(store.importRecords(eq(TENANT_ID), eq(CONNECTOR_ID), any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<SourceRecord> records = invocation.getArgument(4);
                    return records.stream().map(ignored -> ImportResult.createdOne()).toList();
                });
        when(store.completeRun(eq(TENANT_ID), eq(CONNECTOR_ID), any(), any(), any(), eq(true)))
                .thenAnswer(invocation -> invocation.getArgument(4));

        var result = service.runScheduled(scheduledCaller(), CONNECTOR_ID, 10);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.objects()).extracting(value -> value.objectType()).containsExactly(
                "CUSTOMER_TYPE", "CUSTOMER_AREA", "STAFF", "CUSTOMER", "ADDRESS");
        InOrder ordered = inOrder(client);
        for (CrmMasterDataObjectType type : CrmMasterDataObjectType.SYNC_ORDER) {
            ordered.verify(client).collect(any(), eq(CONNECTOR_ID), eq(type), eq(10));
        }
        ArgumentCaptor<CallerIdentity> caller = ArgumentCaptor.forClass(CallerIdentity.class);
        verify(client, org.mockito.Mockito.times(5))
                .collect(caller.capture(), eq(CONNECTOR_ID), any(), eq(10));
        assertThat(caller.getAllValues()).allSatisfy(identity -> {
            assertThat(identity.principalScope()).isEqualTo("SERVICE");
            assertThat(identity.tenantId()).isEqualTo(TENANT_ID);
            assertThat(identity.permissions()).contains("integration:dhb:read");
        });
        verify(discovery, never()).discover(any());
    }

    @Test
    void failedProviderCollectionMarksRunFailedAndDoesNotReconcileAbsence() {
        DhbCrmMasterDataClient client = mock(DhbCrmMasterDataClient.class);
        DhbCrmSyncTargetDiscoveryClient discovery = mock(DhbCrmSyncTargetDiscoveryClient.class);
        CrmMasterDataStore store = mock(CrmMasterDataStore.class);
        CrmMasterDataSyncService service = new CrmMasterDataSyncService(client, discovery, store);
        UUID runId = UUID.randomUUID();
        when(store.startRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(null),
                eq(CrmMasterDataObjectType.CUSTOMER_TYPE), eq(10), eq("SCHEDULED"))).thenReturn(runId);
        when(client.collect(any(), eq(CONNECTOR_ID), eq(CrmMasterDataObjectType.CUSTOMER_TYPE), eq(10)))
                .thenThrow(new IllegalStateException("provider failed"));

        assertThatThrownBy(() -> service.runScheduled(scheduledCaller(), CONNECTOR_ID, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider failed");

        verify(store).failRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(runId), any(), any());
        verify(store, never()).completeRun(any(), any(), any(), any(), any(), anyBoolean());
    }

    private static CallerIdentity scheduledCaller() {
        return new CallerIdentity("SERVICE", UUID.randomUUID(), TENANT_ID,
                null, null, UUID.randomUUID(), 0, 0, 0,
                Set.of("CRM_SYNC_SERVICE"), Set.of("integration:dhb:read"));
    }
}
