package com.rigour.merchant.application.service;

import com.rigour.merchant.application.port.out.CrmMasterDataStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.ImportResult;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.Collected;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.application.port.out.DhbCrmSyncTargetDiscoveryClient;
import com.rigour.merchant.application.port.out.IamStaffDirectoryClient;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.integration.client.ConnectorSyncLeaseClient;
import com.rigour.integration.client.ConnectorSyncLeaseClient.LeaseGuard;
import com.rigour.integration.client.ExternalObjectMappingClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrmMasterDataSyncServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb600-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb600-0000-7000-8000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("019fb600-0000-7000-8000-000000000003");

    @Test
    void scheduledRunUsesErpCompatibleDependencyOrderAndCompletesEachObject() {
        DhbCrmMasterDataClient client = mock(DhbCrmMasterDataClient.class);
        DhbCrmSyncTargetDiscoveryClient discovery = mock(DhbCrmSyncTargetDiscoveryClient.class);
        CrmMasterDataStore store = mock(CrmMasterDataStore.class);
        CrmDictionaryCoverageService dictionaries = mock(CrmDictionaryCoverageService.class);
        LeaseGuard guard = mock(LeaseGuard.class);
        CrmMasterDataSyncService service = syncService(
                client, discovery, store, dictionaries, passthroughLease(guard));
        when(dictionaries.sync(eq(TENANT_ID), any())).thenReturn(Audit.empty());
        when(store.startRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(null), eq(TASK_ID),
                any(), eq(10), eq("SCHEDULED")))
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

        var result = service.runScheduled(scheduledCaller(), CONNECTOR_ID, TASK_ID, 10);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.objects()).extracting(value -> value.objectType()).containsExactly(
                "CUSTOMER_TYPE", "CUSTOMER_AREA", "CUSTOMER", "ADDRESS");
        InOrder ordered = inOrder(client);
        for (CrmMasterDataObjectType type : CrmMasterDataObjectType.SYNC_ORDER) {
            ordered.verify(client).collect(any(), eq(CONNECTOR_ID), eq(type), eq(10));
        }
        ArgumentCaptor<CallerIdentity> caller = ArgumentCaptor.forClass(CallerIdentity.class);
        verify(client, org.mockito.Mockito.times(CrmMasterDataObjectType.SYNC_ORDER.size()))
                .collect(caller.capture(), eq(CONNECTOR_ID), any(), eq(10));
        assertThat(caller.getAllValues()).allSatisfy(identity -> {
            assertThat(identity.principalScope()).isEqualTo("SERVICE");
            assertThat(identity.tenantId()).isEqualTo(TENANT_ID);
            assertThat(identity.permissions()).contains("integration:dhb:read");
        });
        verify(discovery, never()).discover(any());
        verify(store, org.mockito.Mockito.times(CrmMasterDataObjectType.SYNC_ORDER.size()))
                .startRun(eq(TENANT_ID), eq(CONNECTOR_ID),
                eq(null), eq(TASK_ID), any(), eq(10), eq("SCHEDULED"));
        InOrder successBoundaries = inOrder(guard, store);
        for (CrmMasterDataObjectType type : CrmMasterDataObjectType.SYNC_ORDER) {
            successBoundaries.verify(guard).ensureActive();
            successBoundaries.verify(store).completeRun(eq(TENANT_ID), eq(CONNECTOR_ID),
                    any(), eq(type), any(), eq(true));
        }
    }

    @Test
    void failedProviderCollectionMarksRunFailedAndDoesNotReconcileAbsence() {
        DhbCrmMasterDataClient client = mock(DhbCrmMasterDataClient.class);
        DhbCrmSyncTargetDiscoveryClient discovery = mock(DhbCrmSyncTargetDiscoveryClient.class);
        CrmMasterDataStore store = mock(CrmMasterDataStore.class);
        CrmDictionaryCoverageService dictionaries = mock(CrmDictionaryCoverageService.class);
        CrmMasterDataSyncService service = syncService(
                client, discovery, store, dictionaries, passthroughLease());
        UUID runId = UUID.randomUUID();
        when(store.startRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(null), eq(TASK_ID),
                eq(CrmMasterDataObjectType.CUSTOMER_TYPE), eq(10), eq("SCHEDULED"))).thenReturn(runId);
        when(client.collect(any(), eq(CONNECTOR_ID), eq(CrmMasterDataObjectType.CUSTOMER_TYPE), eq(10)))
                .thenThrow(new IllegalStateException("provider failed"));

        assertThatThrownBy(() -> service.runScheduled(scheduledCaller(), CONNECTOR_ID, TASK_ID, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider failed");

        verify(store).failRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(runId), any(), any());
        verify(store, never()).completeRun(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void firstObjectFenceFailureMarksRunFailedWithoutSuccessOrSkipAudit() {
        DhbCrmMasterDataClient client = mock(DhbCrmMasterDataClient.class);
        DhbCrmSyncTargetDiscoveryClient discovery = mock(DhbCrmSyncTargetDiscoveryClient.class);
        CrmMasterDataStore store = mock(CrmMasterDataStore.class);
        CrmDictionaryCoverageService dictionaries = mock(CrmDictionaryCoverageService.class);
        LeaseGuard guard = mock(LeaseGuard.class);
        UUID runId = UUID.randomUUID();
        BusinessException leaseLost = new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                "lease lost before first completion", List.of());
        when(store.startRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(null), eq(TASK_ID),
                eq(CrmMasterDataObjectType.CUSTOMER_TYPE), eq(10), eq("SCHEDULED")))
                .thenReturn(runId);
        when(client.collect(any(), eq(CONNECTOR_ID),
                eq(CrmMasterDataObjectType.CUSTOMER_TYPE), eq(10)))
                .thenReturn(new Collected(CrmMasterDataObjectType.CUSTOMER_TYPE,
                        0, 1, List.of()));
        when(dictionaries.sync(eq(TENANT_ID), any())).thenReturn(Audit.empty());
        doThrow(leaseLost).when(guard).ensureActive();
        CrmMasterDataSyncService service = syncService(
                client, discovery, store, dictionaries, passthroughLease(guard));

        assertThatThrownBy(() -> service.runScheduled(scheduledCaller(), CONNECTOR_ID, TASK_ID, 10))
                .isSameAs(leaseLost);

        verify(store).failRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(runId), any(), same(leaseLost));
        verify(store, never()).completeRun(any(), any(), any(), any(), any(), anyBoolean());
        verify(store, never()).recordSkippedRun(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(store, never()).recordSkippedRuns(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void connectorLeaseConflictPersistsFinishedSkippedRunsForEveryObject() {
        DhbCrmMasterDataClient client = mock(DhbCrmMasterDataClient.class);
        DhbCrmSyncTargetDiscoveryClient discovery = mock(DhbCrmSyncTargetDiscoveryClient.class);
        CrmMasterDataStore store = mock(CrmMasterDataStore.class);
        CrmDictionaryCoverageService dictionaries = mock(CrmDictionaryCoverageService.class);
        ConnectorSyncLeaseClient lease = mock(ConnectorSyncLeaseClient.class);
        when(lease.executeWithLeaseGuard(eq(TENANT_ID), eq(CONNECTOR_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                        "remote details must not be persisted", List.of()));
        when(store.recordSkippedRuns(eq(TENANT_ID), eq(CONNECTOR_ID), eq(TASK_ID),
                eq(CrmMasterDataObjectType.SYNC_ORDER), eq(10),
                eq(CrmMasterDataSyncService.CONNECTOR_LEASE_CONFLICT), any()))
                .thenReturn(CrmMasterDataObjectType.SYNC_ORDER.stream()
                        .map(ignored -> UUID.randomUUID()).toList());
        CrmMasterDataSyncService service = syncService(
                client, discovery, store, dictionaries, lease);

        var result = service.runScheduled(scheduledCaller(), CONNECTOR_ID, TASK_ID, 10);

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.objects()).hasSize(CrmMasterDataObjectType.SYNC_ORDER.size()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("SKIPPED");
            assertThat(item.finishedAt()).isNotNull();
        });
        verify(store).recordSkippedRuns(
                eq(TENANT_ID), eq(CONNECTOR_ID), eq(TASK_ID),
                eq(CrmMasterDataObjectType.SYNC_ORDER), eq(10),
                eq(CrmMasterDataSyncService.CONNECTOR_LEASE_CONFLICT),
                eq("订货宝连接器已有同步任务运行，本轮未执行"));
        verify(store, never()).startRun(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void objectLockConflictPersistsOneSkipAndContinuesRemainingObjects() {
        DhbCrmMasterDataClient client = mock(DhbCrmMasterDataClient.class);
        DhbCrmSyncTargetDiscoveryClient discovery = mock(DhbCrmSyncTargetDiscoveryClient.class);
        CrmMasterDataStore store = mock(CrmMasterDataStore.class);
        CrmDictionaryCoverageService dictionaries = mock(CrmDictionaryCoverageService.class);
        when(store.startRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(null), eq(TASK_ID),
                any(), eq(10), eq("SCHEDULED"))).thenAnswer(invocation -> {
                    CrmMasterDataObjectType type = invocation.getArgument(4);
                    if (type == CrmMasterDataObjectType.CUSTOMER_TYPE) {
                        throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                                "local details must not be persisted", List.of());
                    }
                    return UUID.randomUUID();
                });
        when(store.recordSkippedRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(TASK_ID),
                eq(CrmMasterDataObjectType.CUSTOMER_TYPE), eq(10),
                eq(CrmMasterDataSyncService.OBJECT_SYNC_ALREADY_RUNNING), any()))
                .thenReturn(UUID.randomUUID());
        when(client.collect(any(), eq(CONNECTOR_ID), any(), eq(10)))
                .thenAnswer(invocation -> new Collected(invocation.getArgument(2), 0, 1, List.of()));
        when(dictionaries.sync(eq(TENANT_ID), any())).thenReturn(Audit.empty());
        when(store.completeRun(eq(TENANT_ID), eq(CONNECTOR_ID), any(), any(), any(), eq(true)))
                .thenAnswer(invocation -> invocation.getArgument(4));
        CrmMasterDataSyncService service = syncService(
                client, discovery, store, dictionaries, passthroughLease());

        var result = service.runScheduled(scheduledCaller(), CONNECTOR_ID, TASK_ID, 10);

        assertThat(result.status()).isEqualTo("SUCCEEDED_WITH_WARNINGS");
        assertThat(result.objects()).extracting(value -> value.status())
                .containsExactlyElementsOf(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("SKIPPED"),
                        CrmMasterDataObjectType.SYNC_ORDER.stream().skip(1).map(ignored -> "SUCCEEDED"))
                        .toList());
        verify(store).recordSkippedRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(TASK_ID),
                eq(CrmMasterDataObjectType.CUSTOMER_TYPE), eq(10),
                eq(CrmMasterDataSyncService.OBJECT_SYNC_ALREADY_RUNNING),
                eq("相同对象范围已有同步任务运行，本轮未执行"));
        verify(client, org.mockito.Mockito.times(CrmMasterDataObjectType.SYNC_ORDER.size() - 1))
                .collect(any(), eq(CONNECTOR_ID), any(), eq(10));
    }

    @Test
    void leaseLossAfterWorkStartedIsFailureRatherThanAFalseSkipAudit() {
        DhbCrmMasterDataClient client = mock(DhbCrmMasterDataClient.class);
        DhbCrmSyncTargetDiscoveryClient discovery = mock(DhbCrmSyncTargetDiscoveryClient.class);
        CrmMasterDataStore store = mock(CrmMasterDataStore.class);
        CrmDictionaryCoverageService dictionaries = mock(CrmDictionaryCoverageService.class);
        ConnectorSyncLeaseClient lease = mock(ConnectorSyncLeaseClient.class);
        when(store.startRun(eq(TENANT_ID), eq(CONNECTOR_ID), eq(null), eq(TASK_ID),
                any(), eq(10), eq("SCHEDULED"))).thenAnswer(invocation -> UUID.randomUUID());
        when(client.collect(any(), eq(CONNECTOR_ID), any(), eq(10)))
                .thenAnswer(invocation -> new Collected(invocation.getArgument(2), 0, 1, List.of()));
        when(dictionaries.sync(eq(TENANT_ID), any())).thenReturn(Audit.empty());
        when(store.completeRun(eq(TENANT_ID), eq(CONNECTOR_ID), any(), any(), any(), eq(true)))
                .thenAnswer(invocation -> invocation.getArgument(4));
        when(lease.executeWithLeaseGuard(eq(TENANT_ID), eq(CONNECTOR_ID), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<LeaseGuard, ?> action = invocation.getArgument(2);
            action.apply(() -> { });
            throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                    "lease lost after work", List.of());
        });
        CrmMasterDataSyncService service = syncService(
                client, discovery, store, dictionaries, lease);

        assertThatThrownBy(() -> service.runScheduled(scheduledCaller(), CONNECTOR_ID, TASK_ID, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessage("lease lost after work");
        verify(store, never()).recordSkippedRun(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    private static CallerIdentity scheduledCaller() {
        return new CallerIdentity("SERVICE", UUID.randomUUID(), TENANT_ID,
                null, null, UUID.randomUUID(), 0, 0, 0,
                Set.of("CRM_SYNC_SERVICE"), Set.of("integration:dhb:read"));
    }

    private static ConnectorSyncLeaseClient passthroughLease() {
        return passthroughLease(() -> { });
    }

    private static ConnectorSyncLeaseClient passthroughLease(LeaseGuard guard) {
        ConnectorSyncLeaseClient lease = mock(ConnectorSyncLeaseClient.class);
        when(lease.executeWithLeaseGuard(any(), any(), any())).thenAnswer(invocation -> {
            Function<LeaseGuard, ?> action = invocation.getArgument(2);
            return action.apply(guard);
        });
        return lease;
    }

    private static CrmMasterDataSyncService syncService(
            DhbCrmMasterDataClient client,
            DhbCrmSyncTargetDiscoveryClient discovery,
            CrmMasterDataStore store,
            CrmDictionaryCoverageService dictionaries,
            ConnectorSyncLeaseClient lease) {
        IamStaffDirectoryClient staffDirectory = mock(IamStaffDirectoryClient.class);
        when(staffDirectory.resolveDinghuobaoStaff(any(), any(), any())).thenReturn(List.of());
        return new CrmMasterDataSyncService(client, discovery, store, dictionaries,
                lease, mock(ExternalObjectMappingClient.class), staffDirectory);
    }
}
