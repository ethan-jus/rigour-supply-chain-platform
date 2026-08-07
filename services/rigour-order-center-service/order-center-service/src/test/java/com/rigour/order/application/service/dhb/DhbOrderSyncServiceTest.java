package com.rigour.order.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderImportResult;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.application.port.out.DhbOrderSyncCheckpointStore;
import com.rigour.order.application.port.out.DhbOrderSyncClient;
import com.rigour.shared.context.CallerIdentity;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DhbOrderSyncServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb000-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("019fb000-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb000-0000-7000-8000-000000000010");

    @Test
    void schedulerEntryCallsIntegrationBeforeImportingIntoOrderCenter() {
        DhbOrderSyncClient integration = mock(DhbOrderSyncClient.class);
        DhbOrderImportService importer = mock(DhbOrderImportService.class);
        DhbOrderSyncService service = new DhbOrderSyncService(integration, importer,
                mock(DhbOrderSyncCheckpointStore.class), Clock.systemUTC());
        DhbOrderImportBatch batch = new DhbOrderImportBatch(null, null, null, null);
        when(integration.collect(any(), eq(CONNECTOR_ID), any())).thenReturn(new DhbOrderSyncClient.Collected(
                UUID.fromString("019fb000-0000-7000-8000-000000000011"), "ORDER", 8,
                Set.of("ORDER", "ORDER_DETAIL"), batch));
        when(importer.importBatchInternal(TENANT_ID.toString(), batch))
                .thenReturn(new DhbOrderImportResult(2, 1, 1, 2));

        var result = service.runScheduled(caller(), CONNECTOR_ID, new DhbOrderSyncCommand(true, 5));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.fetched()).isEqualTo(8);
        assertThat(result.changed()).isEqualTo(6);
        InOrder ordered = inOrder(integration, importer);
        ordered.verify(integration).collect(any(), eq(CONNECTOR_ID), any());
        ordered.verify(importer).importBatchInternal(TENANT_ID.toString(), batch);
    }

    private static CallerIdentity caller() {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.fromString("019fb000-0000-7000-8000-000000000003"), 0, 0, 0,
                Set.of("ORDER_OPERATOR"), Set.of("integration:dhb:read", "integration:dhb:write"));
    }
}
