package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.SalesLogisticsModels.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.order.application.service.sales.OrderSalesOrderService;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class SalesLogisticsServiceTest {
    final OrderSalesOrderService orders = mock(OrderSalesOrderService.class);
    final OrderLogisticsLedgerService ledgers = mock(OrderLogisticsLedgerService.class);
    final OrderLogisticsCostStore costs = mock(OrderLogisticsCostStore.class);
    final TransportRecordService transports = mock(TransportRecordService.class);
    final PlatformTransportStore platform = mock(PlatformTransportStore.class);
    final OrderLedgerSourceStore sources = mock(OrderLedgerSourceStore.class);
    final SalesLogisticsService service =
            new SalesLogisticsService(orders, ledgers, costs, transports, platform, sources);
    final JsonMapper json = JsonMapper.builder().build();
    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setup() {
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        user,
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(
                                "order:read",
                                "order:logistics-cost:read",
                                "order:logistics-cost:write")));
        when(orders.salesOrderForLogistics(11L))
                .thenReturn(new OrderSalesOrderStore.LogisticsOrder("SO001", "CONFIRMED"));
        when(ledgers.salesSources(anyList(), anyString(), anyBoolean()))
                .thenReturn(
                        List.of(
                                json.readValue(
                                        "{\"documentNo\":\"SO001\",\"sourceType\":\"SALES_ORDER\",\"cityCode\":\"WH\"}",
                                        LogisticsCostSourceView.class)));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    Detail row(String status, boolean linked) {
        return json.readValue(
                "{\"record\":{\"id\":\"C1\",\"revision\":3,\"statusCode\":\""
                        + status
                        + "\",\"data\":{\"typeCode\":\"DELIVERY\"},\"sources\":"
                        + (linked ? "[{\"documentNo\":\"SO001\"}]" : "[]")
                        + "}}",
                Detail.class);
    }

    @Test
    void reselectingAnAlreadyLinkedConfirmedRecordDoesNotWriteOrDuplicateCost() {
        var old = row("ACTIVE", true);
        when(transports.detail("C1")).thenReturn(old);
        assertThat(service.link(11L, new Link("C1", 1, null))).isSameAs(old);
        verify(transports, never()).saveWithPendingEvidence(any(), any());
        verifyNoInteractions(costs);
    }

    @Test
    void confirmedOrStaleUnlinkedRecordCannotReceiveAnotherSalesRelation() {
        when(transports.detail("C1")).thenReturn(row("VOID", false));
        assertThatThrownBy(() -> service.link(11L, new Link("C1", 3, null)))
                .hasMessageContaining("已作废");
        when(transports.detail("C1")).thenReturn(row("ACTIVE", false));
        assertThatThrownBy(() -> service.link(11L, new Link("C1", 2, null)))
                .hasMessageContaining("记录已变化");
        verify(transports, never()).saveWithPendingEvidence(any(), any());
    }

    @Test
    void salesManualCreationCannotChangePurposeLinkOtherOrdersOrHandEnterHuolala() {
        for (String extra :
                List.of(
                        "\"transportType\":\"OTHER\",\"orderCodes\":[\"SO001\"]",
                        "\"transportType\":\"DELIVERY\",\"orderCodes\":[\"OTHER\"]",
                        "\"transportType\":\"DELIVERY\",\"orderCodes\":[\"SO001\"],\"carrierCode\":\"HLL\""))
            assertThatThrownBy(
                            () ->
                                    service.validateManual(
                                            11L, json.readValue("{" + extra + "}", Draft.class)))
                    .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
    }

    @Test
    void associationCountsOnlyReadVisibleSalesOrderNumbers() {
        when(sources.visibleSalesNos(
                        tenant.toString(), List.of("SO001", "HIDDEN"), "order:logistics-cost:read"))
                .thenReturn(List.of("SO001"));
        when(sources.logisticsUsage(
                        tenant.toString(), List.of("SO001"), "order:logistics-cost:read"))
                .thenReturn(Map.of("SO001", 1));
        assertThat(service.counts(List.of("SO001", "HIDDEN"))).containsOnlyKeys("SO001");
    }

    @Test
    void salesSummaryExcludesVoidAndOnlyCountsCompleteSharesAsCost() {
        var complete = costForSummary("C1", "ACTIVE", "15.53");
        var incomplete = costForSummary("C2", "ACTIVE", "20.00");
        var cancelled = costForSummary("C3", "VOID", "100.00");
        when(costs.linkedSales(tenant.toString(), "SO001", "order:logistics-cost:read"))
                .thenReturn(List.of(complete, incomplete, cancelled));
        when(costs.businessStatuses(tenant.toString(), List.of("C1", "C2"), "order:logistics-cost:read"))
                .thenReturn(Map.of("C1", "COMPLETE", "C2", "INCOMPLETE"));
        when(platform.byCosts(tenant.toString(), List.of("C1", "C2"))).thenReturn(Map.of());
        var result = service.context(11L);
        assertThat(result.transports()).extracting(r -> r.record().id()).containsExactly("C1", "C2");
        assertThat(result.allocatedAmount()).isEqualByComparingTo("35.53");
        assertThat(result.confirmedAmount()).isEqualByComparingTo("15.53");
    }

    @Test
    void selectionKeepsAutomaticShipmentRowsAndSkipsCostSummary() {
        var shipments = mock(ShipmentTransportStore.class);
        service.shipmentStore(shipments);
        var complete = costForSummary("C1", "ACTIVE", "15.53");
        var other = costForSummary("C2", "ACTIVE", "20.00");
        var cancelled = costForSummary("C3", "VOID", "100.00");
        when(costs.linkedSales(tenant.toString(), "SO001", "order:logistics-cost:read"))
                .thenReturn(List.of(complete, other, cancelled));
        var shipment = mock(ShipmentTransportModels.Source.class);
        when(shipments.byCosts(tenant.toString(), List.of("C1", "C2")))
                .thenReturn(Map.of("C1", List.of(shipment)));
        when(platform.byCosts(tenant.toString(), List.of("C1"))).thenReturn(Map.of());

        var context = service.context(11L, true);

        assertThat(context.salesOrder().documentNo()).isEqualTo("SO001");
        assertThat(context.transports()).extracting(r -> r.record().id()).containsExactly("C1");
        assertThat(context.transports().getFirst().shipments()).containsExactly(shipment);
        assertThat(context.allocatedAmount()).isNull();
        assertThat(context.confirmedAmount()).isNull();
        verify(costs, never()).businessStatuses(anyString(), anyList(), anyString());
    }

    LogisticsCostDetailView costForSummary(String id, String status, String amount) {
        return json.readValue(
                """
                {"id":"%s","revision":1,"statusCode":"%s","data":{"amount":%s,"ledger":{"schemaVersion":1,"sources":[
                  {"orderCode":"SO001","linkRoleCode":"COST","allocations":[{"amount":%s}]}]}}}
                """.formatted(id, status, amount, amount), LogisticsCostDetailView.class);
    }

    @Test
    void choosingCompletedPlatformOrderKeepsSameCostIdentityAndFixesSalesPurpose() {
        var old =
                json.readValue(
"""
{"record":{"id":"C1","revision":3,"statusCode":"ACTIVE","data":{"typeCode":null,"amount":137.64,"paymentStatusCode":"PAID"},"sources":[]},
 "platform":{"source":{"revision":1,"sourceDifference":false,"sourceChannel":"API","order":{"line":0,"orderNo":"HL001","status":"已完成","useTime":"2026-11-13 09:00:00","raw":{},"facts":{"billAmount":35}}},"draft":{"orderCodes":[]}}}
""",
                        Detail.class);
        when(transports.detail("C1")).thenReturn(old);
        when(transports.saveWithPendingEvidence(eq("C1"), any())).thenReturn(old);
        assertThat(service.link(11L, new Link("C1", 3, null))).isSameAs(old);
        var saved = org.mockito.ArgumentCaptor.forClass(Draft.class);
        verify(transports).saveWithPendingEvidence(eq("C1"), saved.capture());
        assertThat(saved.getValue().transportType()).isEqualTo("DELIVERY");
        assertThat(saved.getValue().orderCodes()).containsExactly("SO001");
        assertThat(saved.getValue().platformOrderNo()).isEqualTo("HL001");
        assertThat(saved.getValue().paymentStatusCode()).isEqualTo("PAID");
        assertThat(saved.getValue().amount()).isEqualByComparingTo("137.64");
        verifyNoInteractions(costs);
    }
}
