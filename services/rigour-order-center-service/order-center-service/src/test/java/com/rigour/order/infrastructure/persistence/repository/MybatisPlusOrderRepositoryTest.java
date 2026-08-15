package com.rigour.order.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.order.domain.model.order.ImportedOrder;
import com.rigour.order.domain.model.order.Order;
import com.rigour.order.domain.model.order.OrderLine;
import com.rigour.order.infrastructure.persistence.entity.InternalOrderEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalOrderLineEntity;
import com.rigour.order.infrastructure.persistence.entity.OrderSourceRecordEntity;
import com.rigour.order.infrastructure.persistence.mapper.InternalOrderLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalOrderMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalOrderShipmentMapper;
import com.rigour.order.infrastructure.persistence.mapper.OrderSourceRecordMapper;
import com.rigour.shared.outbox.OutboxStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class MybatisPlusOrderRepositoryTest {
    @Test
    void persistsDhbDetailFieldsWithoutDroppingValues() {
        InternalOrderMapper orderMapper = mock(InternalOrderMapper.class);
        InternalOrderLineMapper lineMapper = mock(InternalOrderLineMapper.class);
        InternalOrderShipmentMapper shipmentMapper = mock(InternalOrderShipmentMapper.class);
        OrderSourceRecordMapper sourceRecordMapper = mock(OrderSourceRecordMapper.class);
        OutboxStore outboxStore = mock(OutboxStore.class);
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(sourceRecordMapper.selectCount(any())).thenReturn(0L);
        MybatisPlusOrderRepository repository = new MybatisPlusOrderRepository(orderMapper, lineMapper,
                shipmentMapper, sourceRecordMapper, outboxStore, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC));
        Order order = new Order(null, "tenant-1", "ORDER-RICH", Order.SOURCE_DINGHUOBAO, "ORDER-RICH",
                "RECEIVED", "received", "paided", "normal", new java.math.BigDecimal("10.00"), null, null,
                null, null, null, null, null, "客户", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, "rich-hash", null, null, null,
                "批发客户", "华北区", "管理员", "操作人", "业务员", "13800000000", "辅助员", "2026-08-12 01:00:00",
                "后付", new java.math.BigDecimal("2.5"), new java.math.BigDecimal("1.2"), null,
                new java.math.BigDecimal("9.8"), new java.math.BigDecimal("5"), new java.math.BigDecimal("0"),
                new java.math.BigDecimal("0.5"), "客户留言", "内部沟通",
                "客户公司", "商品", "银行", "账号", "税号", "重点客户", "增值税专用发票");
        OrderLine line = new OrderLine(null, "LINE-RICH", "PRODUCT", "SKU", null, "BARCODE", "商品", "CODE",
                null, null, null, new java.math.BigDecimal("10"), new java.math.BigDecimal("1"),
                new java.math.BigDecimal("10"), "件", null, new java.math.BigDecimal("8"), new java.math.BigDecimal("12"),
                new java.math.BigDecimal("1"), new java.math.BigDecimal("9"), new java.math.BigDecimal("2.5"), "1", "g", "13%",
                new java.math.BigDecimal("1"));

        repository.importOrder(new ImportedOrder(order, List.of(line), List.of(), "{\"list\":1}",
                "{\"detail\":1}", "rich-hash", true));

        ArgumentCaptor<InternalOrderEntity> orderCaptor = ArgumentCaptor.forClass(InternalOrderEntity.class);
        verify(orderMapper).insert(orderCaptor.capture());
        InternalOrderEntity storedOrder = orderCaptor.getValue();
        assertThat(storedOrder.customerType).isEqualTo("批发客户");
        assertThat(storedOrder.settlementMethod).isEqualTo("后付");
        assertThat(storedOrder.freightAmount).isEqualByComparingTo("5");
        assertThat(storedOrder.couponDiscountedAmount).isEqualByComparingTo("0.5");
        assertThat(storedOrder.invoiceTitle).isEqualTo("客户公司");
        assertThat(storedOrder.customerTag).isEqualTo("重点客户");
        assertThat(storedOrder.invoiceType).isEqualTo("增值税专用发票");
        ArgumentCaptor<InternalOrderLineEntity> lineCaptor = ArgumentCaptor.forClass(InternalOrderLineEntity.class);
        verify(lineMapper).insert(lineCaptor.capture());
        InternalOrderLineEntity storedLine = lineCaptor.getValue();
        assertThat(storedLine.purchasePrice).isEqualByComparingTo("8");
        assertThat(storedLine.preSale).isEqualTo("1");
        assertThat(storedLine.contentType).isEqualTo("g");
        assertThat(storedLine.invoiceTax).isEqualTo("13%");
        assertThat(storedLine.contentPercent).isEqualByComparingTo("1");
    }

    @Test
    void listOnlySyncDoesNotClearPreviouslyStoredDetailFields() {
        InternalOrderMapper orderMapper = mock(InternalOrderMapper.class);
        InternalOrderLineMapper lineMapper = mock(InternalOrderLineMapper.class);
        InternalOrderShipmentMapper shipmentMapper = mock(InternalOrderShipmentMapper.class);
        OrderSourceRecordMapper sourceRecordMapper = mock(OrderSourceRecordMapper.class);
        OutboxStore outboxStore = mock(OutboxStore.class);
        InternalOrderEntity existing = new InternalOrderEntity();
        existing.id = "order-id";
        existing.tenantId = "tenant-1";
        existing.sourceOrderNo = "ORDER-LIST";
        existing.sourcePayloadHash = "old-hash";
        existing.detailSyncedAt = java.time.LocalDateTime.of(2026, 8, 11, 2, 0);
        existing.customerType = "批发客户";
        existing.settlementMethod = "后付";
        existing.freightAmount = new java.math.BigDecimal("5");
        existing.customerTag = "重点客户";
        existing.invoiceType = "增值税专用发票";
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(sourceRecordMapper.selectCount(any())).thenReturn(0L);
        MybatisPlusOrderRepository repository = new MybatisPlusOrderRepository(orderMapper, lineMapper,
                shipmentMapper, sourceRecordMapper, outboxStore, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC));
        Order listOnly = new Order(null, "tenant-1", "ORDER-LIST", Order.SOURCE_DINGHUOBAO, "ORDER-LIST",
                "RECEIVED", "received", "paided", "normal", new java.math.BigDecimal("10.00"), null, null,
                null, null, null, null, null, "客户", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, "new-hash", null, null, null);

        repository.importOrder(new ImportedOrder(listOnly, List.of(), List.of(), "{\"list\":2}", null,
                "new-hash", false));

        ArgumentCaptor<InternalOrderEntity> orderCaptor = ArgumentCaptor.forClass(InternalOrderEntity.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        InternalOrderEntity updated = orderCaptor.getValue();
        assertThat(updated.customerType).isEqualTo("批发客户");
        assertThat(updated.settlementMethod).isEqualTo("后付");
        assertThat(updated.freightAmount).isEqualByComparingTo("5");
        assertThat(updated.customerTag).isEqualTo("重点客户");
        assertThat(updated.invoiceType).isEqualTo("增值税专用发票");
    }

    @Test
    void expandsOfficialStockUpAliasToStoredStatusValue() {
        assertEquals(List.of("pricing", "stockup", "stock_up", "shipped", "received"),
                MybatisPlusOrderRepository.sourceStatusValues("pricing,stock_up,shipped,received"));
    }

    @Test
    void acceptsStoredStockupAliasAsWell() {
        assertEquals(List.of("stockup", "stock_up"),
                MybatisPlusOrderRepository.sourceStatusValues("stockup"));
    }

    @Test
    void doesNotWriteAnUnchangedOrderWhenDetailsAreAlreadyStored() {
        InternalOrderMapper orderMapper = mock(InternalOrderMapper.class);
        InternalOrderLineMapper lineMapper = mock(InternalOrderLineMapper.class);
        InternalOrderShipmentMapper shipmentMapper = mock(InternalOrderShipmentMapper.class);
        OrderSourceRecordMapper sourceRecordMapper = mock(OrderSourceRecordMapper.class);
        OutboxStore outboxStore = mock(OutboxStore.class);
        MybatisPlusOrderRepository repository = new MybatisPlusOrderRepository(orderMapper, lineMapper,
                shipmentMapper, sourceRecordMapper, outboxStore, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC));
        String hash = "same-hash";
        InternalOrderEntity existing = new InternalOrderEntity();
        existing.id = "order-id";
        existing.tenantId = "tenant-1";
        existing.sourceOrderNo = "ORDER-1";
        existing.sourcePayloadHash = hash;
        existing.detailSyncedAt = java.time.LocalDateTime.of(2026, 8, 11, 2, 0);
        when(orderMapper.selectOne(any())).thenReturn(existing);

        Order order = new Order(null, "tenant-1", "ORDER-1", Order.SOURCE_DINGHUOBAO, "ORDER-1",
                "RECEIVED", "received", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, hash, existing.detailSyncedAt, existing.createdAt,
                existing.updatedAt);

        var result = repository.importOrder(new ImportedOrder(order, List.of(), List.of(),
                "{}", "{}", hash, true));

        assertEquals(new com.rigour.order.application.port.out.OrderRepository.ImportResult(
                "order-id", false, false), result);
        verify(orderMapper, never()).updateById(any(InternalOrderEntity.class));
        verify(lineMapper, never()).delete(any());
        verify(shipmentMapper, never()).delete(any());
        verify(sourceRecordMapper, never()).insert(any(OrderSourceRecordEntity.class));
        verify(outboxStore, never()).append(any());
    }

    @Test
    void repairsMissingOrderDetailsWhenSourcePayloadHasNotChanged() {
        InternalOrderMapper orderMapper = mock(InternalOrderMapper.class);
        InternalOrderLineMapper lineMapper = mock(InternalOrderLineMapper.class);
        InternalOrderShipmentMapper shipmentMapper = mock(InternalOrderShipmentMapper.class);
        OrderSourceRecordMapper sourceRecordMapper = mock(OrderSourceRecordMapper.class);
        OutboxStore outboxStore = mock(OutboxStore.class);
        MybatisPlusOrderRepository repository = new MybatisPlusOrderRepository(orderMapper, lineMapper,
                shipmentMapper, sourceRecordMapper, outboxStore, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC));
        InternalOrderEntity existing = new InternalOrderEntity();
        existing.id = "order-id";
        existing.tenantId = "tenant-1";
        existing.sourceOrderNo = "ORDER-1";
        existing.sourcePayloadHash = "same-hash";
        existing.detailSyncedAt = java.time.LocalDateTime.of(2026, 8, 11, 2, 0);
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(lineMapper.selectCount(any())).thenReturn(0L);
        when(shipmentMapper.selectCount(any())).thenReturn(0L);

        Order order = new Order(null, "tenant-1", "ORDER-1", Order.SOURCE_DINGHUOBAO, "ORDER-1",
                "RECEIVED", "received", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, "same-hash", existing.detailSyncedAt, existing.createdAt,
                existing.updatedAt);
        ImportedOrder imported = new ImportedOrder(order,
                List.of(new com.rigour.order.domain.model.order.OrderLine(
                        null, "line-1", "product-1", "SKU-1", null, null, "商品", "P-1",
                        null, null, null, null, null, null, null, null)),
                List.of(), "{}", "{\"line\":\"present\"}", "same-hash", true);

        var result = repository.importOrder(imported);

        assertEquals(new com.rigour.order.application.port.out.OrderRepository.ImportResult(
                "order-id", false, true), result);
        verify(orderMapper).updateById(any(InternalOrderEntity.class));
        verify(lineMapper).delete(any());
        verify(lineMapper).insert(any(InternalOrderLineEntity.class));
        verify(sourceRecordMapper, never()).insert(any(OrderSourceRecordEntity.class));
        verify(outboxStore, never()).append(any());
    }

    @Test
    void rewritesOrderDetailsWhenSourcePayloadHasChanged() {
        InternalOrderMapper orderMapper = mock(InternalOrderMapper.class);
        InternalOrderLineMapper lineMapper = mock(InternalOrderLineMapper.class);
        InternalOrderShipmentMapper shipmentMapper = mock(InternalOrderShipmentMapper.class);
        OrderSourceRecordMapper sourceRecordMapper = mock(OrderSourceRecordMapper.class);
        OutboxStore outboxStore = mock(OutboxStore.class);
        MybatisPlusOrderRepository repository = new MybatisPlusOrderRepository(orderMapper, lineMapper,
                shipmentMapper, sourceRecordMapper, outboxStore, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC));
        InternalOrderEntity existing = new InternalOrderEntity();
        existing.id = "order-id";
        existing.tenantId = "tenant-1";
        existing.sourceOrderNo = "ORDER-1";
        existing.sourcePayloadHash = "old-hash";
        existing.detailSyncedAt = java.time.LocalDateTime.of(2026, 8, 11, 2, 0);
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(sourceRecordMapper.selectCount(any())).thenReturn(0L);

        Order order = new Order(null, "tenant-1", "ORDER-1", Order.SOURCE_DINGHUOBAO, "ORDER-1",
                "RECEIVED", "received", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, "new-hash", existing.detailSyncedAt, existing.createdAt,
                existing.updatedAt);
        ImportedOrder imported = new ImportedOrder(order,
                List.of(new com.rigour.order.domain.model.order.OrderLine(
                        null, "line-1", "product-1", "SKU-NEW", null, null, "商品", "P-1",
                        null, null, null, null, null, null, null, null)),
                List.of(), "{}", "{\"line\":\"changed\"}", "new-hash", true);

        var result = repository.importOrder(imported);

        assertEquals(new com.rigour.order.application.port.out.OrderRepository.ImportResult(
                "order-id", false, true), result);
        verify(orderMapper).updateById(any(InternalOrderEntity.class));
        verify(lineMapper).delete(any());
        verify(lineMapper).insert(any(InternalOrderLineEntity.class));
        verify(shipmentMapper).delete(any());
        ArgumentCaptor<OrderSourceRecordEntity> sourceRecords =
                ArgumentCaptor.forClass(OrderSourceRecordEntity.class);
        verify(sourceRecordMapper, times(2)).insert(sourceRecords.capture());
        assertEquals(List.of("LIST", "DETAIL"), sourceRecords.getAllValues().stream()
                .map(record -> record.payloadType).toList());
        assertEquals(List.of("{}", "{\"line\":\"changed\"}"), sourceRecords.getAllValues().stream()
                .map(record -> record.payloadJson).toList());
        assertNotEquals(sourceRecords.getAllValues().get(0).payloadHash,
                sourceRecords.getAllValues().get(1).payloadHash);
        verify(outboxStore).append(any());
    }
}
