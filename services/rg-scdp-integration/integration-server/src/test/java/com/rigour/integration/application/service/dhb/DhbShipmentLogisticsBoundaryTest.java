package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.application.port.out.*;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.ShipmentTransportModels;
import com.rigour.settings.client.BusinessDictionaryBatchClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/** 发货事实先独立留存，不能以留存成功替代库存履约成功。 */
class DhbShipmentLogisticsBoundaryTest {
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), task = UUID.randomUUID();
    final UUID raw = UUID.randomUUID();
    final Instant from = Instant.parse("2026-11-01T00:00:00Z");
    final Instant to = Instant.parse("2026-11-13T00:00:00Z");
    final DhbSyncStore store = mock(DhbSyncStore.class);
    final DhbClient client = mock(DhbClient.class);
    final OrderSalesOrderProjectionClient order = mock(OrderSalesOrderProjectionClient.class);
    final ErpStockOutProjectionClient erp = mock(ErpStockOutProjectionClient.class);
    final BusinessDictionaryBatchClient dictionaries = mock(BusinessDictionaryBatchClient.class);
    final JsonMapper json = JsonMapper.builder().build();
    DhbOrderSyncService service;

    @BeforeEach
    void setup() {
        when(store.loadTask(tenant, task))
                .thenReturn(
                        new DhbSyncStore.SyncTaskContext(
                                tenant,
                                task,
                                connector,
                                "shipments",
                                "ORDER",
                                "ACTIVE",
                                "https://example.test",
                                "test",
                                "ACTIVE",
                                100,
                                1,
                                0,
                                true));
        when(store.beginRun(eq(tenant), any(), eq(task), eq(from), eq(to)))
                .thenAnswer(ignored -> new DhbSyncStore.SyncRunStarted(UUID.randomUUID(), null));
        when(store.persistRawObject(
                        eq(tenant),
                        eq(connector),
                        any(),
                        eq("SHIPMENT_CONTENT"),
                        eq("FH001"),
                        any(),
                        any(),
                        anyMap(),
                        any()))
                .thenReturn(new DhbSyncStore.RawObjectPersistResult(raw, "checksum-1", true));
        when(dictionaries.sync(any(), anyString(), anyCollection()))
                .thenReturn(
                        new BusinessDictionaryBatchClient.Audit(
                                0,
                                Map.of(),
                                List.of(),
                                List.of(
                                        new BusinessDictionaryBatchClient.ResolvedValue(
                                                "COST_CARRIER", "顺丰", "COST_CARRIER", "SF"))));
        when(client.getShipments(any(), any()))
                .thenAnswer(
                        call -> {
                            DhbClient.ShipmentQuery query = call.getArgument(1);
                            return new DhbClient.Page<>(
                                    query.page(),
                                    "F".equals(query.isApi()) ? 1 : 0,
                                    "F".equals(query.isApi())
                                            ? List.of(shipment("DH001"))
                                            : List.of());
                        });
        when(client.getShipmentContent(any(), eq("FH001")))
                .thenReturn(
                        new DhbClient.ShipmentDetail(
                                "FH001",
                                Map.of(
                                        "ships_id",
                                        "D1",
                                        "type_id",
                                        "10",
                                        "status",
                                        "receivedin",
                                        "orders_num",
                                        "DH001",
                                        "stock_name",
                                        "来源仓库",
                                        "logistics_name",
                                        "顺丰",
                                        "express_num",
                                        "SF001",
                                        "list",
                                        List.of(Map.of("goods_num", "G1", "ships_number", 3)))));
        when(order.syncShipmentTransport(any(), any()))
                .thenAnswer(
                        call ->
                                new ShipmentTransportModels.Source(
                                        "source-1", "cost-1", call.getArgument(1), null, false));
        service =
                new DhbOrderSyncService(
                        store,
                        client,
                        order,
                        erp,
                        mock(HrDhbStaffSyncClient.class),
                        dictionaries,
                        1);
    }

    void mappedSalesOrder() {
        when(store.findActiveMapping(tenant, connector, "SALES_ORDER", "DH001"))
                .thenReturn(
                        new DhbSyncStore.ExternalObjectMapping(
                                UUID.randomUUID(),
                                "SALES_ORDER",
                                "DH001",
                                "DH001",
                                "ORDER",
                                "SALES_ORDER",
                                11L,
                                "SO001",
                                "ACTIVE",
                                "order-checksum"));
        when(order.salesOrder(any(), eq(11L)))
                .thenReturn(
                        json.readValue(
                                "{\"id\":11,\"customerCodeSnapshot\":\"CUS1\",\"regionCode\":\"WH\"}",
                                SalesOrderDetailView.class));
    }

    DhbClient.Shipment shipment(String orderNo) {
        return new DhbClient.Shipment(
                "D1",
                "FH001",
                orderNo,
                "receivedin",
                "已发货",
                "10",
                "销售出库",
                "CUS1",
                "星河门店",
                null,
                "W1",
                "来源仓库",
                null,
                from.plusSeconds(60),
                "顺丰",
                "SF001",
                null,
                from,
                from.plusSeconds(60),
                Map.of());
    }

    com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView run() {
        return service.runOrderPull(
                DhbSyncOrchestrationService.serviceCaller(tenant),
                task,
                new SyncRunCommand(from, to, null, null, null, "SHIPMENT"),
                500,
                ignored -> {});
    }

    @Test
    void missingWarehouseDoesNotBlockLogisticsOrPretendInventoryWasProcessed() {
        mappedSalesOrder();
        var result = run();
        var command = ArgumentCaptor.forClass(ShipmentTransportModels.Command.class);
        verify(order).syncShipmentTransport(any(), command.capture());
        assertThat(command.getValue().salesOrderId()).isEqualTo(11L);
        assertThat(command.getValue().warehouseId()).isNull();
        assertThat(command.getValue().carrierCode()).isEqualTo("SF");
        assertThat(command.getValue().trackingNumbers()).containsExactly("SF001");
        assertThat(command.getValue().goods()).hasSize(1);
        assertThat(command.getValue().sourceFields()).containsEntry("stock_name", "来源仓库");
        assertThat(json.writeValueAsString(command.getValue()))
                .doesNotContain("billAmount", "freightAmount");
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.acceptedCount()).isZero();
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.errorMessage()).contains("1张销售发货的物流已保存", "已出库发货", "库存台账待补齐").doesNotContain("checkpoint");
        verify(store)
                .markRawFailed(
                        eq(tenant),
                        eq(raw),
                        eq("DHB_SHIPMENT_WAREHOUSE_MAPPING_MISSING"),
                        anyString());
        verify(store, never()).markRawProcessed(any(), any());
        verifyNoInteractions(erp);
        verify(order, never()).createSalesShipment(any(), any());
    }

    @Test
    void unavailableSourceOrderNeverInventsAnOrderOrAssociatesLogistics() {
        var result = run();
        assertThat(result.status()).isEqualTo("PARTIAL");
        verify(order, never()).syncShipmentTransport(any(), any());
        verify(order, never()).createSalesOrder(any(), any());
        verify(store)
                .markRawFailed(
                        eq(tenant), eq(raw), eq("DHB_SHIPMENT_SOURCE_ORDER_MISMATCH"), anyString());
        verifyNoInteractions(erp);
    }

    @Test
    void missingGoodsMappingAlsoLeavesLogisticsSavedAndInventoryUntouched() {
        mappedSalesOrder();
        when(store.findActiveMapping(tenant, connector, "WAREHOUSE", "W1"))
                .thenReturn(
                        new DhbSyncStore.ExternalObjectMapping(
                                UUID.randomUUID(),
                                "WAREHOUSE",
                                "W1",
                                "W1",
                                "ERP",
                                "WAREHOUSE",
                                3L,
                                "W001",
                                "ACTIVE",
                                "warehouse-checksum"));
        var result = run();
        verify(order).syncShipmentTransport(any(), argThat(c -> c.warehouseId().equals(3L)));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.errorMessage()).contains("1张销售发货的物流已保存");
        verify(store, never()).markRawProcessed(any(), any());
        verifyNoInteractions(erp);
        verify(order, never()).createSalesShipment(any(), any());
    }

    @Test
    void missingSalesTargetIsRejectedWithoutWritingLogistics() {
        mappedSalesOrder();
        when(order.salesOrder(any(), eq(11L))).thenReturn(null);
        assertThat(run().status()).isEqualTo("PARTIAL");
        verify(order, never()).syncShipmentTransport(any(), any());
        verify(store)
                .markRawFailed(
                        eq(tenant), eq(raw), eq("DHB_SHIPMENT_ORDER_TARGET_MISSING"), anyString());
        verifyNoInteractions(erp);
    }

    @Test
    void failedLogisticsWriteIsNotReportedAsPreservedAndCanBeRetriedWithSameIdentity() {
        mappedSalesOrder();
        when(order.syncShipmentTransport(any(), any()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenAnswer(
                        call ->
                                new ShipmentTransportModels.Source(
                                        "source-1", "cost-1", call.getArgument(1), null, false));
        var failed = run();
        assertThat(failed.errorMessage()).doesNotContain("物流已留存");
        var retried = run();
        assertThat(retried.errorMessage()).contains("1张销售发货的物流已保存");
        var commands = ArgumentCaptor.forClass(ShipmentTransportModels.Command.class);
        verify(order, times(2)).syncShipmentTransport(any(), commands.capture());
        assertThat(commands.getAllValues().getFirst()).isEqualTo(commands.getAllValues().getLast());
        verifyNoInteractions(erp);
    }

    @Test
    void emptyLogisticsResponseCannotBeCountedAsSaved() {
        mappedSalesOrder();
        when(order.syncShipmentTransport(any(), any())).thenReturn(null);
        assertThat(run().errorMessage()).doesNotContain("物流已留存");
        verify(store)
                .markRawFailed(
                        eq(tenant), eq(raw), eq("DHB_SHIPMENT_PROJECTION_FAILED"), anyString());
        verifyNoInteractions(erp);
    }

    @Test
    void missingOrderIsFetchedByExactNumberAndSpecificCustomerFailureIsRetained() {
        when(client.getOrderContent(any(), eq("DH001"))).thenReturn(new DhbClient.OrderDetail("DH001", "stockup",
                java.math.BigDecimal.TEN, Map.of("ClientNO", "CUS-MISSING", "OrderDate", "2026-11-12 10:00:00")));
        when(store.persistRawObject(any(), any(), any(), eq("ORDER_DETAIL"), eq("DH001"), any(), any(), anyMap(), any()))
                .thenReturn(new DhbSyncStore.RawObjectPersistResult(UUID.randomUUID(), "source-order", true));
        assertThat(run().status()).isEqualTo("PARTIAL");
        verify(client).getOrderContent(any(), eq("DH001"));
        verify(client, never()).getOrders(any(), any());
        verify(store).markRawFailed(eq(tenant), eq(raw), eq("DHB_ORDER_MAPPING_MISSING"),
                argThat(m -> m.contains("DH001") && m.contains("客户") && m.contains("CUS-MISSING")));
        verify(order, never()).createSalesOrder(any(), any());
        verify(order, never()).syncShipmentTransport(any(), any());
    }

    @Test
    void shipmentFailureRemainsPendingEvenWhenItsLogisticsWasSaved() {
        mappedSalesOrder();
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "incrementalWork", work);
        when(work.pendingCount(tenant, connector, "SHIPMENT")).thenReturn(1L);
        assertThat(run().errorMessage()).contains("物流已保存", "1 条单据待处理");
        verify(work).waitForManual(eq(tenant), eq(connector), eq("SHIPMENT"), any(), any());
    }

    @Test
    void retriesOnlyPendingShipmentOutsideWindowAndDoesNotFetchItTwiceInSameRun() {
        mappedSalesOrder();
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "incrementalWork", work);
        var item = new DhbIncrementalWorkStore.Item("FH001", "queued", json.writeValueAsString(shipment("DH001")), from.minusSeconds(60));
        when(work.pending(eq(tenant), eq(connector), eq("SHIPMENT"), eq(100), any())).thenReturn(List.of(item));
        run();
        verify(client).getShipmentContent(any(), eq("FH001"));
        verify(order).syncShipmentTransport(any(), any());
        verify(work).waitForManual(eq(tenant), eq(connector), eq("SHIPMENT"), eq(item), any());
    }

    @Test
    void unchangedAppliedShipmentSkipsDetailAndNeverRecreatesCost() {
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "incrementalWork", work);
        when(work.stage(eq(tenant), eq(connector), eq("SHIPMENT"), anyList(), any())).thenReturn(Set.of("FH001"));
        assertThat(run().duplicateCount()).isEqualTo(1L);
        verify(client, never()).getShipmentContent(any(), any());
        verifyNoInteractions(order, erp);
    }

    @Test
    void singleObjectReplayAcknowledgesOnlyItsOriginalQueuedFingerprint() {
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "incrementalWork", work);
        var item = new DhbIncrementalWorkStore.Item("FH001", "queued", "{}", from);
        when(work.find(tenant, connector, "SHIPMENT", "FH001")).thenReturn(item);
        when(store.beginRun(eq(tenant), any(), eq(task), isNull(), isNull()))
                .thenReturn(new DhbSyncStore.SyncRunStarted(UUID.randomUUID(), null));
        service.runOrderPull(DhbSyncOrchestrationService.serviceCaller(tenant), task,
                new SyncRunCommand(null, null, null, "SALES_SHIPMENT", "FH001"), 100, x -> {});
        verify(work).waitForManual(eq(tenant), eq(connector), eq("SHIPMENT"), eq(item), any());
        verify(client, never()).getShipments(any(), any());
    }

    @Test
    void dependencyRefreshInvalidatesMissingMappingCacheAndPreservesActualShippingFact() {
        mappedSalesOrder();
        var repair = mock(DhbShipmentDependencyRepair.class);
        var session = mock(DhbShipmentDependencyRepair.Session.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "shipmentDependencyRepair", repair);
        when(repair.session(tenant, connector, 500)).thenReturn(session);
        when(session.refresh(anyList())).thenAnswer(call -> {
            if (call.getArgument(0).equals(List.of("WAREHOUSE"))) {
                when(store.findActiveMapping(tenant, connector, "WAREHOUSE", "W1"))
                    .thenReturn(new DhbSyncStore.ExternalObjectMapping(UUID.randomUUID(), "WAREHOUSE", "W1", "W1",
                            "ERP", "WAREHOUSE", 3L, "W001", "ACTIVE", "warehouse-checksum"));
            }
            return new DhbShipmentDependencyRepair.Result(true, "资料已补同步");
        });
        run();
        verify(order).syncShipmentTransport(any(), argThat(c -> c.warehouseId().equals(3L) && "receivedin".equals(c.sourceStatus())));
        verify(session).refresh(List.of("WAREHOUSE"));
        // 商品仍缺失时物流事实已保留，但不凭空产生一笔库存扣减。
        verifyNoInteractions(erp);
    }

    @Test
    void existingExactSourceOrderIsReusedAndNeverCreatedAgain() {
        var target = json.readValue("{\"id\":11,\"orderNo\":\"SO001\",\"sourceSystemCode\":\"DINGHUOBAO\",\"sourceOrderNo\":\"DH001\",\"orderStatusCode\":\"COMPLETED\"}", SalesOrderDetailView.class);
        when(order.findSalesOrderBySource(any(), eq("DINGHUOBAO"), eq("DH001"))).thenReturn(Optional.of(target));
        doAnswer(call -> {
            DhbSyncStore.ExternalObjectMappingWrite c = call.getArgument(2);
            when(store.findActiveMapping(tenant, connector, "SALES_ORDER", "DH001"))
                    .thenReturn(new DhbSyncStore.ExternalObjectMapping(UUID.randomUUID(), "SALES_ORDER", "DH001", "DH001",
                            "ORDER", "SALES_ORDER", c.internalObjectId(), c.internalObjectNo(), "ACTIVE", null));
            return null;
        }).when(store).upsertExternalObjectMapping(eq(tenant), any(), any());
        when(order.salesOrder(any(), eq(11L))).thenReturn(target);
        run();
        verify(client, never()).getOrderContent(any(), any());
        verify(order, never()).createSalesOrder(any(), any());
        verify(order).syncShipmentTransport(any(), argThat(c -> c.salesOrderId().equals(11L)));
    }

    @Test void ordinarySyncDoesNotRepeatDependencyRetrievalAfterManualFallback() {
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "incrementalWork", work);
        when(work.awaitingManual(eq(tenant),eq(connector),eq("SHIPMENT"),anySet())).thenReturn(Set.of("FH001"));
        when(work.pendingCount(tenant,connector,"SHIPMENT")).thenReturn(1L);
        var result = run();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.duplicateCount()).isZero();
        verify(client,never()).getShipmentContent(any(),any());
        verify(client,never()).getOrderContent(any(),any());
        verifyNoInteractions(order,erp);
    }

    @Test void wholeOrderSyncCannotReportSuccessWhileManualShipmentWorkRemains() {
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "incrementalWork", work);
        when(work.awaitingManual(eq(tenant),eq(connector),eq("SHIPMENT"),anySet())).thenReturn(Set.of("FH001"));
        when(work.pendingCount(tenant,connector,"SHIPMENT")).thenReturn(1L);
        var page = DhbClient.PageRequest.first(100);
        when(client.getOrders(any(),any())).thenReturn(new DhbClient.Page<>(page,0,List.of()));
        when(client.getReceipts(any(),any())).thenReturn(new DhbClient.Page<>(page,0,List.of()));
        when(client.getPayments(any(),any())).thenReturn(new DhbClient.Page<>(page,0,List.of()));
        when(client.getTransferOrders(any(),any())).thenReturn(new DhbClient.Page<>(page,0,List.of()));
        var result=service.runOrderPull(DhbSyncOrchestrationService.serviceCaller(tenant),task,
                new SyncRunCommand(from,to,null,null,null,null),500,ignored->{});
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.errorMessage()).contains("1 条单据待处理", "手工补录");
        verify(client,never()).getShipmentContent(any(),any());
        verify(order,never()).syncShipmentTransport(any(),any());
        verify(order,never()).createSalesOrder(any(),any());
        verifyNoInteractions(erp);
    }
}
