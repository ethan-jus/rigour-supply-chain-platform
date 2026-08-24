package com.rigour.erp.application.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rigour.erp.api.v1.model.ErpDataSyncCommand;
import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.port.out.ErpSyncRunAuditStore;
import com.rigour.erp.application.port.out.ErpSyncRunAuditStore.ScheduledSkipReason;
import com.rigour.erp.application.service.product.ProductMasterDataSyncService;
import com.rigour.erp.application.service.supply.SupplyDataSyncService;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ErpDataSyncServiceTest {
    private static final UUID RUN_ID = UUID.fromString("019fb100-0000-7000-8000-000000000011");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb100-0000-7000-8000-000000000012");
    private static final UUID SOURCE_TASK_ID = UUID.fromString("019fb100-0000-7000-8000-000000000013");

    @Test
    void dispatchesProductObjectThroughUnifiedEndpoint() {
        ProductMasterDataSyncService product = mock(ProductMasterDataSyncService.class);
        SupplyDataSyncService supply = mock(SupplyDataSyncService.class);
        ErpDataSyncResult expected = result("BRAND");
        when(product.run(MasterDataObjectType.BRAND, 3)).thenReturn(expected);

        ErpDataSyncResult actual = new ErpDataSyncService(product, supply, mock(ErpSyncRunAuditStore.class))
                .run(new ErpDataSyncCommand("brand", 3));

        assertThat(actual).isSameAs(expected);
        verify(product).run(MasterDataObjectType.BRAND, 3);
        verifyNoInteractions(supply);
    }

    @Test
    void dispatchesSupplyObjectThroughUnifiedEndpoint() {
        ProductMasterDataSyncService product = mock(ProductMasterDataSyncService.class);
        SupplyDataSyncService supply = mock(SupplyDataSyncService.class);
        ErpDataSyncResult expected = result("INVENTORY");
        when(supply.run(SupplyDataObjectType.INVENTORY, 5)).thenReturn(expected);

        ErpDataSyncResult actual = new ErpDataSyncService(product, supply, mock(ErpSyncRunAuditStore.class))
                .run(new ErpDataSyncCommand("inventory", 5));

        assertThat(actual).isSameAs(expected);
        verify(supply).run(SupplyDataObjectType.INVENTORY, 5);
        verifyNoInteractions(product);
    }

    @Test
    void dispatchesScheduledObjectThroughTheSameUnifiedService() {
        ProductMasterDataSyncService product = mock(ProductMasterDataSyncService.class);
        SupplyDataSyncService supply = mock(SupplyDataSyncService.class);
        CallerIdentity caller = new CallerIdentity("SERVICE", UUID.randomUUID(),
                UUID.fromString("019fb100-0000-7000-8000-000000000013"), null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ERP_SYNC_SERVICE"),
                Set.of("integration:dhb:read"));
        ErpDataSyncResult expected = result("BRAND");
        when(product.runScheduled(caller, CONNECTOR_ID, MasterDataObjectType.BRAND, 3))
                .thenReturn(expected);

        ErpDataSyncResult actual = new ErpDataSyncService(product, supply, mock(ErpSyncRunAuditStore.class))
                .runScheduled(caller, CONNECTOR_ID, SOURCE_TASK_ID, new ErpDataSyncCommand("brand", 3));

        assertThat(actual).isSameAs(expected);
        verify(product).runScheduled(caller, CONNECTOR_ID, MasterDataObjectType.BRAND, 3);
        verifyNoInteractions(supply);
    }

    @Test
    void scheduledSkipIsAuditedAndReturnedAsSkippedResult() {
        ProductMasterDataSyncService product = mock(ProductMasterDataSyncService.class);
        SupplyDataSyncService supply = mock(SupplyDataSyncService.class);
        ErpSyncRunAuditStore audit = mock(ErpSyncRunAuditStore.class);
        CallerIdentity caller = scheduledCaller();
        when(product.runScheduled(caller, CONNECTOR_ID, MasterDataObjectType.BRAND, 3))
                .thenThrow(ErpScheduledSyncSkipException.connectorLeaseConflict("BRAND"));
        when(audit.recordScheduledSkip(caller.tenantId(), CONNECTOR_ID, SOURCE_TASK_ID,
                "BRAND", 3, ScheduledSkipReason.CONNECTOR_LEASE_CONFLICT)).thenReturn(RUN_ID);

        ErpDataSyncResult actual = new ErpDataSyncService(product, supply, audit)
                .runScheduled(caller, CONNECTOR_ID, SOURCE_TASK_ID, new ErpDataSyncCommand("brand", 3));

        assertThat(actual.runId()).isEqualTo(RUN_ID);
        assertThat(actual.objectType()).isEqualTo("BRAND");
        assertThat(actual.status()).isEqualTo("SKIPPED");
        assertThat(actual.fetched()).isZero();
        verify(audit).recordScheduledSkip(caller.tenantId(), CONNECTOR_ID, SOURCE_TASK_ID,
                "BRAND", 3, ScheduledSkipReason.CONNECTOR_LEASE_CONFLICT);
        verifyNoInteractions(supply);
    }

    @Test
    void rejectsUnknownObjectWithoutCallingDomainSync() {
        ProductMasterDataSyncService product = mock(ProductMasterDataSyncService.class);
        SupplyDataSyncService supply = mock(SupplyDataSyncService.class);

        assertThatThrownBy(() -> new ErpDataSyncService(product, supply, mock(ErpSyncRunAuditStore.class))
                .run(new ErpDataSyncCommand("UNKNOWN", 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PRODUCT_SPU")
                .hasMessageContaining("INVENTORY");
        verifyNoInteractions(product, supply);
    }

    private static ErpDataSyncResult result(String objectType) {
        return new ErpDataSyncResult(RUN_ID, objectType, "SUCCEEDED", CONNECTOR_ID,
                1, 1, 0, 0, 0, 0, Map.of(), 1, Instant.parse("2026-08-10T12:00:00Z"));
    }

    private static CallerIdentity scheduledCaller() {
        return new CallerIdentity("SERVICE", UUID.randomUUID(),
                UUID.fromString("019fb100-0000-7000-8000-000000000013"), null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ERP_SYNC_SERVICE"),
                Set.of("integration:dhb:read"));
    }
}
