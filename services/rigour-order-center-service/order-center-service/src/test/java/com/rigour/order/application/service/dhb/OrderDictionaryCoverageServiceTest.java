package com.rigour.order.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderDictionaryCoverageServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000001");

    @Test
    void orderAndLogisticsSyncIncludeBusinessTypesButNotBooleanFlags() {
        List<Observation> observed = new ArrayList<>();
        BusinessDictionaryBatchClient client = mock(BusinessDictionaryBatchClient.class);
        when(client.sync(any(), any(), any())).thenAnswer(invocation -> {
            Collection<Observation> values = invocation.getArgument(2);
            observed.addAll(values);
            return Audit.empty();
        });
        OrderDictionaryCoverageService service = new OrderDictionaryCoverageService(client);
        DhbOrderImportBatch.OrderLineItem line = mock(DhbOrderImportBatch.OrderLineItem.class);
        when(line.preSale()).thenReturn("T");
        when(line.contentType()).thenReturn("g");
        when(line.invoiceTax()).thenReturn("T");
        DhbOrderImportBatch.OrderItem order = mock(DhbOrderImportBatch.OrderItem.class);
        when(order.lines()).thenReturn(List.of(line));
        when(order.shipmentSnapshots()).thenReturn(List.of());
        DhbOrderImportBatch.ShipmentLogisticsLineItem shippedLine =
                mock(DhbOrderImportBatch.ShipmentLogisticsLineItem.class);
        when(shippedLine.listType()).thenReturn("buy");
        DhbOrderImportBatch.ShipmentLogisticsRecord shipped =
                mock(DhbOrderImportBatch.ShipmentLogisticsRecord.class);
        when(shipped.lines()).thenReturn(List.of(shippedLine));
        DhbOrderImportBatch.WaitStockItem wait = mock(DhbOrderImportBatch.WaitStockItem.class);
        when(wait.listType()).thenReturn("gift");
        DhbOrderImportBatch.ShipmentLogisticsItem logistics =
                new DhbOrderImportBatch.ShipmentLogisticsItem(
                        "O-1", List.of(shipped), List.of(wait), "{}", "a".repeat(64));

        service.sync(TENANT_ID, "ORDER", new DhbOrderImportBatch(
                List.of(order), List.of(), List.of(logistics), List.of(), List.of()));

        assertThat(observed).extracting(Observation::dictCode, Observation::fieldCode,
                        Observation::sourceValue)
                .contains(tuple("DHB_ORDER_LINE_TYPE", "orderLine.contentType", "g"),
                        tuple("DHB_GOODS_LIST_TYPE", "shipmentLogisticsLine.listType", "buy"),
                        tuple("DHB_GOODS_LIST_TYPE", "waitStock.listType", "gift"));
        assertThat(observed).extracting(Observation::dictCode)
                .doesNotContain("DHB_ORDER_PRE_SALE", "DHB_INVOICE_TAX_FLAG",
                        "DHB_ORDER_SEND_TYPE", "DHB_ORDER_DEVICE", "DHB_RETURN_DELIVERY_MODE");
    }
}
