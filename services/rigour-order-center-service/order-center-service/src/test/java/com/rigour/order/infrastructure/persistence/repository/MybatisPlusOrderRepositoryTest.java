package com.rigour.order.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.order.domain.model.order.ImportedOrder;
import com.rigour.order.domain.model.order.Order;
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
import tools.jackson.databind.json.JsonMapper;

class MybatisPlusOrderRepositoryTest {
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
        verify(sourceRecordMapper).insert(any(OrderSourceRecordEntity.class));
        verify(outboxStore).append(any());
    }
}
