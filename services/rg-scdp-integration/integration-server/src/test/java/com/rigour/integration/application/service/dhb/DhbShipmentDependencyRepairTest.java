package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.application.port.out.*;
import com.rigour.merchant.api.v1.model.SyncResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

class DhbShipmentDependencyRepairTest {
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), task = UUID.randomUUID();
    final DhbIntegrationStore store = mock(DhbIntegrationStore.class);
    final ErpDhbDomainSyncClient erp = mock(ErpDhbDomainSyncClient.class);
    final CrmDhbDomainSyncClient crm = mock(CrmDhbDomainSyncClient.class);
    final DhbShipmentDependencyRepair service = new DhbShipmentDependencyRepair(store, erp, crm);

    @Test
    void concurrentShipmentsRefreshEachCatalogueOnceAndNeverSyncBusinessOrInventory()
            throws Exception {
        when(store.configuredProductMasterSyncTargets())
                .thenReturn(List.of(new SyncTargetView(task, tenant, connector)));
        when(erp.sync(
                        any(),
                        eq(connector),
                        eq(task),
                        anyString(),
                        eq(100),
                        eq("SCHEDULED"),
                        isNull(),
                        isNull()))
                .thenAnswer(c -> result(c.getArgument(3)));
        var session = service.session(tenant, connector, 100);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Callable<DhbShipmentDependencyRepair.Result>>();
            for (int i = 0; i < 32; i++)
                jobs.add(() -> session.refresh(List.of("PRODUCT_SKU", "SKU")));
            for (var value : pool.invokeAll(jobs)) assertThat(value.get().attempted()).isTrue();
        }
        for (String type : List.of("CATEGORY", "BRAND", "SPECIFICATION", "TAG", "PRODUCT_SPU"))
            verify(erp)
                    .sync(
                            any(),
                            eq(connector),
                            eq(task),
                            eq(type),
                            eq(100),
                            eq("SCHEDULED"),
                            isNull(),
                            isNull());
        verifyNoMoreInteractions(erp);
        verifyNoInteractions(crm);
    }

    @Test
    void missingOrConflictingTargetNeverUsesAnotherTenantOrConnector() {
        when(store.configuredSupplyChainSyncTargets())
                .thenReturn(
                        List.of(
                                new SyncTargetView(task, UUID.randomUUID(), connector),
                                new SyncTargetView(task, tenant, UUID.randomUUID())));
        service.session(tenant, connector, 100).refresh(List.of("WAREHOUSE"));
        when(store.configuredSupplyChainSyncTargets())
                .thenReturn(
                        List.of(
                                new SyncTargetView(task, tenant, connector),
                                new SyncTargetView(UUID.randomUUID(), tenant, connector)));
        service.session(tenant, connector, 100).refresh(List.of("WAREHOUSE"));
        verifyNoInteractions(erp, crm);
    }

    @Test
    void ambiguousRemoteOutcomeIsNotRetriedOncePerShipmentAndNewRunCanRecover() {
        when(store.configuredCrmMasterSyncTargets())
                .thenReturn(List.of(new SyncTargetView(task, tenant, connector)));
        when(crm.syncObject(
                        any(),
                        eq(connector),
                        eq(task),
                        eq("CUSTOMER"),
                        eq(100),
                        isNull(),
                        isNull()))
                .thenThrow(new CrmDhbDomainSyncClient.OutcomeUnknown("timeout", null))
                .thenReturn(new SyncResult(UUID.randomUUID(), "SUCCEEDED", List.of()));
        var session = service.session(tenant, connector, 100);
        assertThat(session.refresh(List.of("CUSTOMER")).message()).contains("未取得完整结果");
        session.refresh(List.of("CUSTOMER"));
        verify(crm)
                .syncObject(
                        any(),
                        eq(connector),
                        eq(task),
                        eq("CUSTOMER"),
                        eq(100),
                        isNull(),
                        isNull());
        service.session(tenant, connector, 100).refresh(List.of("CUSTOMER"));
        verify(crm, times(2))
                .syncObject(
                        any(),
                        eq(connector),
                        eq(task),
                        eq("CUSTOMER"),
                        eq(100),
                        isNull(),
                        isNull());
    }

    @Test
    void unrelatedMissingDictionaryNeverTriggersCatalogueOrInventorySynchronization() {
        assertThat(service.session(tenant, connector, 100).refresh(List.of("REGION")).attempted())
                .isFalse();
        verifyNoInteractions(store, erp, crm);
    }

    ErpDataSyncResult result(String type) {
        return new ErpDataSyncResult(
                UUID.randomUUID(),
                type,
                "SUCCEEDED",
                connector,
                1,
                0,
                0,
                1,
                0,
                0,
                Map.of(),
                Map.of(),
                1,
                Instant.now());
    }
}
