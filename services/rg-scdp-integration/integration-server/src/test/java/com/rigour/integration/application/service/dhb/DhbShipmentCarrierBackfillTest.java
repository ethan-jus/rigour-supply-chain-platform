package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.DictionarySourceMappingView;
import com.rigour.integration.application.port.out.DictionarySourceMappingStore;
import com.rigour.integration.application.port.out.DictionarySourceMappingStore.MappedShipmentCarrier;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.order.api.v1.model.ShipmentTransportModels.CarrierBackfill;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DhbShipmentCarrierBackfillTest {
    @Test
    void retriesFailedAndFullBatchesAndOnlyCompletesCurrentMappingVersion() {
        var mappings = mock(DictionarySourceMappingStore.class);
        var order = mock(OrderSalesOrderProjectionClient.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        var worker = new DhbShipmentCarrierBackfill(mappings, order, transactions);
        UUID tenant = UUID.randomUUID();
        String tenantId = tenant.toString();
        var mapping = new DictionarySourceMappingView(12L, "DHB", "DHB_SHIPMENT_LOGISTICS",
                "COST_CARRIER", "shipment.logistics", "极兔速递", "COST_CARRIER", "JT",
                "MAPPED", true, 2, null, null);
        when(mappings.pendingShipmentCarrierBackfills(eq(0L), anyInt()))
                .thenReturn(List.of(new MappedShipmentCarrier(tenantId, mapping)));
        when(mappings.lockShipmentCarrierBackfill(tenantId, 12L, 2))
                .thenReturn(false, true, true, true);
        when(order.backfillShipmentCarrier(any(), any()))
                .thenThrow(new IllegalStateException("Order unavailable"))
                .thenReturn(CarrierBackfill.BATCH_SIZE, 4);

        worker.backfill();
        verifyNoInteractions(order);
        worker.backfill();
        worker.backfill();
        verify(mappings, never()).completeShipmentCarrierBackfill(anyString(), anyLong(), anyInt());

        worker.backfill();
        verify(mappings).completeShipmentCarrierBackfill(tenantId, 12L, 2);
        verify(order, times(3)).backfillShipmentCarrier(argThat(caller -> {
            assertThat(caller.tenantId()).isEqualTo(tenant);
            assertThat(caller.permissions()).contains("order:logistics-cost:sync-source");
            return true;
        }), eq(new CarrierBackfill("极兔速递", "JT")));
    }
}
