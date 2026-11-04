package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.application.port.out.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

class DhbOrderSyncProgressTest {
    final UUID tenant = UUID.randomUUID(),
            connector = UUID.randomUUID(),
            taskId = UUID.randomUUID();
    final DhbSyncStore store = mock(DhbSyncStore.class);
    final DhbClient client = mock(DhbClient.class);
    final OrderSalesOrderProjectionClient projection = mock(OrderSalesOrderProjectionClient.class);
    final Instant from = Instant.parse("2026-09-03T16:00:00Z"),
            to = Instant.parse("2026-09-22T07:58:00Z");
    final SyncRunCommand command = new SyncRunCommand(from, to, null, null, null, "RECEIPT");

    DhbOrderSyncService service() {
        when(store.loadTask(tenant, taskId))
                .thenReturn(
                        new DhbSyncStore.SyncTaskContext(
                                tenant,
                                taskId,
                                connector,
                                "orders",
                                "ORDER",
                                "ACTIVE",
                                "https://example.test",
                                "test",
                                "ACTIVE",
                                100,
                                1,
                                0,
                                true));
        when(store.beginRun(eq(tenant), any(), eq(taskId), eq(from), eq(to)))
                .thenReturn(new DhbSyncStore.SyncRunStarted(UUID.randomUUID(), null));
        return new DhbOrderSyncService(store, client, projection, mock(HrDhbStaffSyncClient.class));
    }

    @Test
    void missingVoucherBatchDownloadsOnlyExactReceiptAndNeverProjectsMoney() {
        var worker=service();var storage=mock(ProductMediaStorage.class);
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"fundAttachmentStorage",storage);
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"fundAttachmentKeyFactory",new DhbAttachmentObjectKeyFactory("dev/fund-attachments"));
        var candidate=new com.rigour.order.api.v1.model.HistorySyncModels.VoucherBackfillCandidate(1,"lease",
                List.of(new com.rigour.order.api.v1.model.HistorySyncModels.VoucherReceiptReference("FR1","DH1")));
        when(projection.claimVoucherBackfill(any(),any())).thenReturn(List.of(candidate),List.of());
        when(client.getPayments(any(),any())).thenAnswer(call->{
            DhbClient.PaymentQuery query=call.getArgument(1);
            var attrs=Map.<String,Object>of("resource",List.of(Map.of("file_url","https://example.test/voucher","old_name","v.png")));
            var exact=new DhbClient.Payment("FP1","FP1","FR1","DH1",null,null,null,null,BigDecimal.TEN,"pend_receipted",to,to,to,null,null,null,null,null,attrs);
            var other=new DhbClient.Payment("FP2","FP2","FR2","DH1",null,null,null,null,BigDecimal.TEN,"pend_receipted",to,to,to,null,null,null,null,null,attrs);
            return new DhbClient.Page<>(query.page(),2,"pend_receipted".equals(query.status())?List.of(exact,other):List.of());
        });
        when(client.downloadAttachment(any(),any())).thenReturn(new DhbClient.DownloadedFile(new byte[]{1,2,3},"image/png"));
        when(projection.syncReceiptVouchers(any(),any())).thenReturn(new com.rigour.order.api.v1.model.HistorySyncModels.Intake("SYNCED",null,null));
        var result=worker.backfillVouchers(DhbSyncOrchestrationService.serviceCaller(tenant),taskId,10,s->{});
        assertThat(result.fetched()).isEqualTo(1);assertThat(result.repaired()).isEqualTo(1);
        verify(projection).syncReceiptVouchers(any(),argThat(c->c.receiptNo().equals("FR1") && c.sourcePaymentNo().equals("FP1") && c.objectKeys().size()==1));
        verify(client,times(1)).downloadAttachment(any(),any());
        verify(projection).finishVoucherBackfill(any(),argThat(c->c.paymentId()==1 && c.state().equals("SYNCED")));
        verify(projection,never()).registerReceipt(any(),any());
        verify(store,never()).finishRun(any(),any(),any(),any(),any(),any(),any(),anyLong(),anyLong(),anyLong(),anyLong(),any(),any(),any());
    }

    @Test
    void sourceEmptyAndMissingMappingAreRecordedWithoutFabricatingAttachments() {
        var worker=service();
        var noMapping=new com.rigour.order.api.v1.model.HistorySyncModels.VoucherBackfillCandidate(1,"a",List.of());
        var mapped=new com.rigour.order.api.v1.model.HistorySyncModels.VoucherBackfillCandidate(2,"b",List.of(new com.rigour.order.api.v1.model.HistorySyncModels.VoucherReceiptReference("FR1","DH1")));
        when(projection.claimVoucherBackfill(any(),any())).thenReturn(List.of(noMapping),List.of(mapped),List.of());
        when(client.getPayments(any(),any())).thenAnswer(call->{DhbClient.PaymentQuery q=call.getArgument(1);return new DhbClient.Page<>(q.page(),1,List.of(new DhbClient.Payment("FP1","FP1","FR1","DH1",null,null,null,null,BigDecimal.TEN,"pend_receipted",to,to,to,null,null,null,null,null,Map.of("resource",List.of()))));});
        var result=worker.backfillVouchers(DhbSyncOrchestrationService.serviceCaller(tenant),taskId,10,s->{});
        assertThat(result.unmapped()).isEqualTo(2);
        verify(projection).finishVoucherBackfill(any(),argThat(c->c.paymentId()==1 && c.state().equals("MAPPING_MISSING")));
        verify(projection).finishVoucherBackfill(any(),argThat(c->c.paymentId()==2 && c.state().equals("SOURCE_EMPTY")));
        verify(projection,never()).syncReceiptVouchers(any(),any());verify(client,never()).downloadAttachment(any(),any());
    }

    @Test
    void receiptProgressIsReportedForEveryPageWithoutImportingRecordsBeyondTheCutoff() {
        var worker = service();
        var later =
                new DhbClient.Receipt(
                        "later",
                        "later",
                        "order",
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ONE,
                        "pend_receipted",
                        to,
                        to,
                        to.plusSeconds(1),
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of());
        when(client.getReceipts(any(), any()))
                .thenAnswer(
                        call -> {
                            DhbClient.ReceiptQuery query = call.getArgument(1);
                            assertThat(query.updatedFrom()).isEqualTo(from);
                            return new DhbClient.Page<>(query.page(), 1, List.of(later));
                        });
        var stages = new ArrayList<String>();
        var result =
                worker.runOrderPull(
                        DhbSyncOrchestrationService.serviceCaller(tenant),
                        taskId,
                        command,
                        500,
                        stages::add);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.acceptedCount()).isZero();
        verifyNoInteractions(projection);
        verify(client, times(3)).getReceipts(any(), any());
        assertThat(stages).hasSize(3).allMatch(s -> s.contains("第 1 页已处理"));
    }

    @Test
    void incompleteReceiptPaginationStillFailsInsteadOfAdvancing() {
        var worker = service();
        when(client.getReceipts(any(), any()))
                .thenAnswer(
                        call -> {
                            DhbClient.ReceiptQuery query = call.getArgument(1);
                            return new DhbClient.Page<>(query.page(), 200, List.of());
                        });
        assertThatThrownBy(
                        () ->
                                worker.runOrderPull(
                                        DhbSyncOrchestrationService.serviceCaller(tenant),
                                        taskId,
                                        command,
                                        1,
                                        s -> {}))
                .hasMessageContaining("DHB_PAGE_LIMIT_REACHED");
        verifyNoInteractions(projection);
    }

    @Test
    void appliedSameVersionSkipsAllDetailAndProjectionRequests() {
        var worker = service();
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "incrementalWork", work);
        var summaries = java.util.stream.IntStream.range(0, 100).mapToObj(i ->
                new DhbClient.OrderSummary("O" + i, "O" + i, "submitted", BigDecimal.TEN,
                        from, from, "C1", null, Map.of())).toList();
        when(client.getOrders(any(), any()))
                .thenReturn(
                        new DhbClient.Page<>(
                                DhbClient.PageRequest.first(100), 100, summaries));
        when(work.stage(eq(tenant), eq(connector), eq("SALES_ORDER"), anyList(), any()))
                .thenReturn(summaries.stream().map(DhbClient.OrderSummary::orderNumber).collect(java.util.stream.Collectors.toSet()));
        var result =
                worker.runOrderPull(
                        DhbSyncOrchestrationService.serviceCaller(tenant),
                        taskId,
                        new SyncRunCommand(from, to, null, null, null, "SALES_ORDER"),
                        500,
                        s -> {});
        assertThat(result.duplicateCount()).isEqualTo(100);
        verify(work).stage(eq(tenant), eq(connector), eq("SALES_ORDER"), argThat(items -> items.size() == 100), any());
        verify(client, never()).getOrderContent(any(), any());
        verifyNoInteractions(projection);
    }

    @Test
    void failedItemIsDurableBeforeReadCursorCanAdvance() {
        var worker = service();
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "incrementalWork", work);
        var missingNumber =
                new DhbClient.OrderSummary(
                        null, null, "submitted", BigDecimal.TEN, from, from, "C1", null, Map.of());
        when(client.getOrders(any(), any()))
                .thenReturn(
                        new DhbClient.Page<>(
                                DhbClient.PageRequest.first(100), 1, List.of(missingNumber)));
        when(work.pendingCount(tenant, connector, "SALES_ORDER")).thenReturn(1L);
        var result =
                worker.runOrderPull(
                        DhbSyncOrchestrationService.serviceCaller(tenant),
                        taskId,
                        new SyncRunCommand(from, to, null, null, null, "SALES_ORDER"),
                        500,
                        s -> {});
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.errorCode()).isEqualTo("DHB_DURABLE_PROJECTION_PENDING");
        assertThat(result.rejectedCount()).isEqualTo(1);
        var sequence = inOrder(work);
        sequence.verify(work).stage(eq(tenant), eq(connector), eq("SALES_ORDER"), anyList(), any());
        sequence.verify(work)
                .complete(eq(tenant), eq(connector), eq("SALES_ORDER"), any(), eq(false), any());
    }

    @Test
    void unresolvedCustomerDefersOnlyItsOrderAndNextCycleRetriesWithoutRescanningHistory() {
        var worker = service();
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "incrementalWork", work);
        var waiting = new java.util.concurrent.atomic.AtomicReference<DhbIncrementalWorkStore.Item>();
        var repaired = new java.util.concurrent.atomic.AtomicBoolean();
        when(work.stage(eq(tenant), eq(connector), eq("SALES_ORDER"), anyList(), any())).thenAnswer(call -> {
            List<DhbIncrementalWorkStore.Item> items = call.getArgument(3);
            items.stream().filter(item -> item.id().equals("WAITING")).findFirst().ifPresent(waiting::set);
            return Set.of();
        });
        when(work.pending(eq(tenant), eq(connector), eq("SALES_ORDER"), anyInt(), any()))
                .thenAnswer(call -> waiting.get() == null ? List.of() : List.of(waiting.get()));
        doAnswer(call -> {
            DhbIncrementalWorkStore.Item item = call.getArgument(3);
            if (item.id().equals("WAITING") && Boolean.TRUE.equals(call.getArgument(4))) waiting.set(null);
            return null;
        }).when(work).complete(any(), any(), any(), any(), anyBoolean(), any());
        when(work.pendingCount(tenant, connector, "SALES_ORDER")).thenAnswer(call -> waiting.get() == null ? 0L : 1L);
        var summaries = List.of(
                new DhbClient.OrderSummary("WAITING", "WAITING", "stockup", BigDecimal.TEN, from, from, "C-WAITING", null, Map.of("ClientNO", "C-WAITING")),
                new DhbClient.OrderSummary("GOOD", "GOOD", "stockup", BigDecimal.TEN, from, from, "C-GOOD", null, Map.of("ClientNO", "C-GOOD")));
        when(client.getOrders(any(), any())).thenReturn(new DhbClient.Page<>(DhbClient.PageRequest.first(100), 2, summaries),
                new DhbClient.Page<>(DhbClient.PageRequest.first(100), 0, List.of()));
        when(store.persistRawObject(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new DhbSyncStore.RawObjectPersistResult(UUID.randomUUID(), "hash", true));
        when(client.getOrderContent(any(), any())).thenAnswer(call -> new DhbClient.OrderDetail(call.getArgument(1), "stockup", BigDecimal.TEN,
                Map.of("OrderProduct", List.of(Map.of("Guid", "P1", "OptionsGoodsNo", "S1", "Name", "商品", "ContentNumber", "1", "ContentPrice", "10", "Units", "个")))));
        when(store.findActiveMapping(eq(tenant), eq(connector), any(), any())).thenAnswer(call -> {
            String type = call.getArgument(2), id = call.getArgument(3);
            boolean customer = "CUSTOMER".equals(type);
            if (customer && "C-WAITING".equals(id) && !repaired.get()) return null;
            if (!customer && !Set.of("PRODUCT_SPU", "PRODUCT_SKU").contains(type)) return null;
            return new DhbSyncStore.ExternalObjectMapping(UUID.randomUUID(), type, id, id,
                    customer ? "CRM" : "ERP", customer ? "CUSTOMER" : "PRODUCT", 1L, id, "ACTIVE", null);
        });
        when(projection.registerSourceOrder(any(), any())).thenReturn(
                new com.rigour.order.api.v1.model.HistorySyncModels.Intake("UPDATED", null, null));
        var orderCommand = new SyncRunCommand(from, to, null, null, null, "SALES_ORDER");
        var first = worker.runOrderPull(DhbSyncOrchestrationService.serviceCaller(tenant), taskId, orderCommand, 500, s -> {});
        assertThat(first.acceptedCount()).isEqualTo(1);
        assertThat(first.rejectedCount()).isEqualTo(1);
        assertThat(first.errorCode()).isEqualTo("DHB_DURABLE_PROJECTION_PENDING");
        verify(projection).registerSourceOrder(any(), argThat(order -> order.sourceNo().equals("GOOD")));
        repaired.set(true);
        var next = worker.runOrderPull(DhbSyncOrchestrationService.serviceCaller(tenant), taskId, orderCommand, 500, s -> {});
        assertThat(next.status()).isEqualTo("SUCCEEDED");
        assertThat(next.acceptedCount()).isEqualTo(1);
        assertThat(waiting.get()).isNull();
        verify(projection).registerSourceOrder(any(), argThat(order -> order.sourceNo().equals("WAITING")));
        verify(client, times(1)).getOrderContent(any(), eq("GOOD"));
        verify(client, times(2)).getOrderContent(any(), eq("WAITING"));
    }

    @Test
    void durableWriteFailureCannotBeReportedAsCaptured() {
        var worker = service();
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "incrementalWork", work);
        var summary =
                new DhbClient.OrderSummary(
                        "O1", "O1", "submitted", BigDecimal.TEN, from, from, "C1", null, Map.of());
        when(client.getOrders(any(), any()))
                .thenReturn(
                        new DhbClient.Page<>(
                                DhbClient.PageRequest.first(100), 1, List.of(summary)));
        when(work.stage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("queue unavailable"));
        assertThatThrownBy(
                        () ->
                                worker.runOrderPull(
                                        DhbSyncOrchestrationService.serviceCaller(tenant),
                                        taskId,
                                        new SyncRunCommand(
                                                from, to, null, null, null, "SALES_ORDER"),
                                        500,
                                        s -> {}))
                .hasMessageContaining("queue unavailable");
        verify(client, never()).getOrderContent(any(), any());
        verifyNoInteractions(projection);
    }
    @Test void expiredPaymentRefreshRemainsPendingAndDoesNotBlockAnotherCancellation() {
        var worker = service();
        var work = mock(DhbIncrementalWorkStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "incrementalWork", work);
        var historical = Instant.parse("2026-08-01T00:00:00Z");
        var missing = new DhbClient.Payment("MISSING","MISSING","FR1","DH1",null,null,null,null,BigDecimal.TEN,
                "pend_receipted",historical,historical,historical,null,null,null,null,null,Map.of("resource",List.of(Map.of("file_url","https://a/expired"))));
        var canceled = new DhbClient.Payment("CANCELED","CANCELED","FR2","DH2",null,null,null,null,BigDecimal.TEN,
                "canceled",historical,historical,historical,null,null,null,null,null,Map.of());
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var first = new DhbIncrementalWorkStore.Item("MISSING","f1",json.writeValueAsString(missing),historical);
        var second = new DhbIncrementalWorkStore.Item("CANCELED","f2",json.writeValueAsString(canceled),historical);
        when(work.pending(eq(tenant),eq(connector),eq("PAYMENT"),anyInt(),any())).thenReturn(List.of(first,second));
        when(work.pendingCount(tenant,connector,"PAYMENT")).thenReturn(1L);
        when(client.getPayments(any(),any())).thenAnswer(call -> {
            DhbClient.PaymentQuery q = call.getArgument(1);
            var rows = "DH2".equals(q.orderNumber()) && "canceled".equals(q.status()) ? List.of(canceled) : List.<DhbClient.Payment>of();
            return new DhbClient.Page<>(q.page(),rows.size(),rows);
        });
        when(projection.syncReceiptVouchers(any(),any())).thenReturn(new com.rigour.order.api.v1.model.HistorySyncModels.Intake("SYNCED",null,null));
        var base = DhbSyncOrchestrationService.serviceCaller(tenant);
        var scheduled = new com.rigour.shared.context.CallerIdentity("SERVICE",base.principalId(),tenant,null,null,
                UUID.randomUUID(),0,0,0,Set.of("DHB_PROTECT_HISTORY"),base.permissions());
        var result = worker.runOrderPull(scheduled,taskId,
                new SyncRunCommand(from,to,null,null,null,"PAYMENT"),500,s1 -> {});
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.errorCode()).isEqualTo("DHB_DURABLE_PROJECTION_PENDING");
        verify(work).complete(eq(tenant),eq(connector),eq("PAYMENT"),eq(first),eq(false),any());
        verify(work).complete(eq(tenant),eq(connector),eq("PAYMENT"),eq(second),eq(true),any());
        verify(client,never()).downloadAttachment(any(),any());
        verify(projection).syncReceiptVouchers(any(),argThat(c -> c.sourcePaymentNo().equals("CANCELED") && c.sourceStatus().equals("CANCELLED")));
        verify(projection).claimVoucherBackfill(any(),argThat(c -> c.connectorId().equals(connector) && c.limit()==1));
        verifyNoMoreInteractions(projection);
    }

}
