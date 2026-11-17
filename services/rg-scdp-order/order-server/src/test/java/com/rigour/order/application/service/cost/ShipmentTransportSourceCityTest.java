package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;

class ShipmentTransportSourceCityTest {
    final UUID tenant = UUID.randomUUID();
    final ShipmentTransportStore store = mock(ShipmentTransportStore.class);
    final OrderSalesOrderStore orders = mock(OrderSalesOrderStore.class);
    final OrderLogisticsLedgerService ledgers = mock(OrderLogisticsLedgerService.class);
    final OrderLogisticsReferences refs = mock(OrderLogisticsReferences.class);
    final ShipmentTransportService service =
            new ShipmentTransportService(store, orders, ledgers, refs);

    @BeforeEach
    void setup() {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "SERVICE",
                        UUID.randomUUID(),
                        tenant,
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("DHB_ORDER_SYNC_SERVICE"),
                        Set.of("order:logistics-cost:sync-source")));
        var order = mock(SalesOrderDetailView.class);
        when(order.orderNo()).thenReturn("DH1");
        when(orders.salesOrder(tenant.toString(), 1L)).thenReturn(Optional.of(order));
        var source = mock(LogisticsCostSourceView.class);
        when(source.documentNo()).thenReturn("DH1");
        when(source.cityCode()).thenReturn("SOURCE-AREA");
        when(ledgers.sources(any(), anyString())).thenReturn(List.of(source));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    ShipmentTransportModels.Command command() {
        return new ShipmentTransportModels.Command(
                UUID.randomUUID(),
                "S1",
                "FH1",
                1L,
                null,
                "receivedin",
                Instant.parse("2026-11-16T01:02:03Z"),
                Instant.parse("2026-11-16T01:03:00Z"),
                null,
                "来源承运方",
                null,
                null,
                List.of("TRACK1"),
                null,
                Map.of("sourceArea", "SOURCE-AREA"),
                List.of());
    }

    @Test
    void unmappedSourceAreaPreservesPendingTransportWithoutInventingCityOrFee() {
        when(refs.cities(anyString())).thenReturn(Map.of("CITY1", "经营城市"));
        var c = command();
        service.sync(c);
        var draft = ArgumentCaptor.forClass(LogisticsCostCommand.class);
        verify(store)
                .sync(
                        eq(tenant.toString()),
                        anyString(),
                        same(c),
                        draft.capture(),
                        isNull(),
                        isNull());
        assertThat(draft.getValue().cityCode()).isNull();
        assertThat(draft.getValue().amount()).isNull();
        assertThat(draft.getValue().ledger().sources()).isEmpty();
        verify(refs, never()).city(anyString(), anyString());
    }

    @Test
    void realAuthorizationFailureDoesNotFallBackToUnscopedWrite() {
        when(refs.cities(anyString()))
                .thenThrow(new AuthorizationDeniedException("order:logistics-cost:read"));
        assertThatThrownBy(() -> service.sync(command()))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
}
