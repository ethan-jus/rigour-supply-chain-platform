package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.order.api.v1.OrderEventType;
import com.rigour.order.api.v1.model.OrderImportedEvent;
import com.rigour.order.application.port.out.OrderRepository;
import com.rigour.order.domain.model.order.ImportedOrder;
import com.rigour.order.domain.model.order.Order;
import com.rigour.order.domain.model.order.OrderLine;
import com.rigour.order.domain.model.order.OrderShipment;
import com.rigour.order.infrastructure.persistence.entity.InternalOrderEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalOrderLineEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalOrderShipmentEntity;
import com.rigour.order.infrastructure.persistence.entity.OrderSourceRecordEntity;
import com.rigour.order.infrastructure.persistence.mapper.InternalOrderLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalOrderMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalOrderShipmentMapper;
import com.rigour.order.infrastructure.persistence.mapper.OrderSourceRecordMapper;
import com.rigour.shared.outbox.OutboxMessage;
import com.rigour.shared.outbox.OutboxStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 内部订单聚合持久化。
 *
 * <p>订货宝同步只更新 source_* 字段，已经存在的 internal_status 永远不被外部状态覆盖，
 * 这样后续自研订单流程可以独立演进。</p>
 */
@Repository
public class MybatisPlusOrderRepository implements OrderRepository {
    private final InternalOrderMapper orderMapper;
    private final InternalOrderLineMapper lineMapper;
    private final InternalOrderShipmentMapper shipmentMapper;
    private final OrderSourceRecordMapper sourceRecordMapper;
    private final OutboxStore outboxStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MybatisPlusOrderRepository(InternalOrderMapper orderMapper, InternalOrderLineMapper lineMapper,
                                      InternalOrderShipmentMapper shipmentMapper,
                                      OrderSourceRecordMapper sourceRecordMapper, OutboxStore outboxStore,
                                      ObjectMapper objectMapper, Clock clock) {
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.shipmentMapper = shipmentMapper;
        this.sourceRecordMapper = sourceRecordMapper;
        this.outboxStore = outboxStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public List<Order> findPage(String tenantId, OrderFilter filter) {
        QueryWrapper<InternalOrderEntity> query = Wrappers.query();
        query.eq("tenant_id", tenantId);
        appendFilters(query, filter);
        query.orderByDesc("ordered_at").orderByDesc("id")
                .last("LIMIT " + filter.step() + " OFFSET " + filter.begin());
        return orderMapper.selectList(query).stream().map(MybatisPlusOrderRepository::toDomain).toList();
    }

    @Override
    public long count(String tenantId, OrderFilter filter) {
        QueryWrapper<InternalOrderEntity> query = Wrappers.query();
        query.eq("tenant_id", tenantId);
        appendFilters(query, filter);
        Long count = orderMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    @Override
    public InternalOrderDetailData findDetail(String tenantId, String sourceOrderNo) {
        InternalOrderEntity entity = findEntity(tenantId, sourceOrderNo);
        if (entity == null) return null;
        List<OrderLine> lines = lineMapper.selectList(Wrappers.<InternalOrderLineEntity>query()
                        .eq("order_id", entity.id).orderByAsc("source_line_id"))
                .stream().map(MybatisPlusOrderRepository::toDomain).toList();
        List<OrderShipment> shipments = shipmentMapper.selectList(Wrappers.<InternalOrderShipmentEntity>query()
                        .eq("order_id", entity.id).orderByAsc("source_shipment_no"))
                .stream().map(MybatisPlusOrderRepository::toDomain).toList();
        return new InternalOrderDetailData(toDomain(entity), lines, shipments, entity.detailSyncedAt != null);
    }

    @Transactional
    @Override
    public ImportResult importOrder(ImportedOrder imported) {
        Order incoming = imported.order();
        LocalDateTime now = LocalDateTime.now(clock);
        InternalOrderEntity existing = findEntity(incoming.tenantId(), incoming.sourceOrderNo());
        boolean changed = existing == null || !Objects.equals(existing.sourcePayloadHash, imported.payloadHash());
        boolean detailNeedsImport = imported.detailIncluded()
                && (existing == null || existing.detailSyncedAt == null || changed
                || !orderDetailsComplete(existing.id, imported.lines().size(), imported.shipments().size()));
        if (existing != null && !changed && !detailNeedsImport) {
            // 来源摘要和本地详情均已存在时，重复同步不得触碰主表、明细、来源记录或事件。
            return new ImportResult(existing.id, false, false);
        }
        InternalOrderEntity entity = toEntity(incoming, imported.payloadHash(), existing, now);
        if (existing == null) orderMapper.insert(entity);
        else if (changed || detailNeedsImport) orderMapper.updateById(entity);

        if (detailNeedsImport) replaceDetails(entity.id, imported.lines(), imported.shipments(), now);
        if (changed) {
            appendSourceRecord(entity, incoming, imported, now);
            appendOrderEvent(entity, changed && existing == null, now);
        }
        return new ImportResult(entity.id, existing == null, changed || detailNeedsImport);
    }

    /** 来源摘要未变化时仍校验本地明细行数，修复被人工删除或部分落库的明细。 */
    private boolean orderDetailsComplete(String orderId, int expectedLineCount, int expectedShipmentCount) {
        Long lineCount = lineMapper.selectCount(Wrappers.<InternalOrderLineEntity>query()
                .eq("order_id", orderId));
        Long shipmentCount = shipmentMapper.selectCount(Wrappers.<InternalOrderShipmentEntity>query()
                .eq("order_id", orderId));
        return countOrZero(lineCount) == expectedLineCount
                && countOrZero(shipmentCount) == expectedShipmentCount;
    }

    private static long countOrZero(Long count) {
        return count == null ? 0L : count;
    }

    private void replaceDetails(String orderId, List<OrderLine> lines, List<OrderShipment> shipments,
                                LocalDateTime now) {
        lineMapper.delete(Wrappers.<InternalOrderLineEntity>query().eq("order_id", orderId));
        for (OrderLine line : lines) {
            InternalOrderLineEntity entity = new InternalOrderLineEntity();
            entity.id = UUID.randomUUID().toString();
            entity.orderId = orderId;
            entity.sourceLineId = line.sourceLineId();
            entity.sourceProductGuid = line.sourceProductGuid();
            entity.skuNo = line.skuNo();
            entity.sourceOptionsGoodsNo = line.sourceOptionsGoodsNo();
            entity.sourceBarcode = line.sourceBarcode();
            entity.productName = line.productName();
            entity.productCode = line.productCode();
            entity.specificationFirst = line.specificationFirst();
            entity.specificationSecond = line.specificationSecond();
            entity.specificationName = line.specificationName();
            entity.unitPrice = line.unitPrice();
            entity.quantity = line.quantity();
            entity.lineAmount = line.lineAmount();
            entity.unit = line.unit();
            entity.remark = line.remark();
            entity.createdAt = now;
            entity.updatedAt = now;
            lineMapper.insert(entity);
        }
        shipmentMapper.delete(Wrappers.<InternalOrderShipmentEntity>query().eq("order_id", orderId));
        for (OrderShipment shipment : shipments) {
            InternalOrderShipmentEntity entity = new InternalOrderShipmentEntity();
            entity.id = UUID.randomUUID().toString();
            entity.orderId = orderId;
            entity.sourceShipmentNo = shipment.sourceShipmentNo();
            entity.status = shipment.status();
            entity.shipmentDate = shipment.shipmentDate();
            entity.stockUpTime = shipment.stockUpTime();
            entity.createdAt = now;
            entity.updatedAt = now;
            shipmentMapper.insert(entity);
        }
    }

    private void appendSourceRecord(InternalOrderEntity entity, Order order, ImportedOrder imported,
                                    LocalDateTime now) {
        String payload = imported.detailIncluded() ? imported.rawDetailPayload() : imported.rawListPayload();
        if (payload == null || payload.isBlank()) payload = "{}";
        OrderSourceRecordEntity record = new OrderSourceRecordEntity();
        record.id = UUID.randomUUID().toString();
        record.tenantId = order.tenantId();
        record.orderId = entity.id;
        record.sourceSystem = order.sourceSystem();
        record.sourceOrderNo = order.sourceOrderNo();
        record.payloadType = imported.detailIncluded() ? "DETAIL" : "LIST";
        record.payloadJson = payload;
        record.payloadHash = imported.payloadHash();
        record.receivedAt = now;
        Long existing = sourceRecordMapper.selectCount(Wrappers.<OrderSourceRecordEntity>query()
                .eq("tenant_id", record.tenantId)
                .eq("source_system", record.sourceSystem)
                .eq("source_order_no", record.sourceOrderNo)
                .eq("payload_type", record.payloadType)
                .eq("payload_hash", record.payloadHash));
        if (existing != null && existing > 0) return;
        sourceRecordMapper.insert(record);
    }

    private void appendOrderEvent(InternalOrderEntity entity, boolean created, LocalDateTime now) {
        String eventType = (created ? OrderEventType.ORDER_IMPORTED : OrderEventType.ORDER_SOURCE_UPDATED).code();
        OrderImportedEvent payload = new OrderImportedEvent(entity.id, entity.orderNo, entity.sourceSystem,
                entity.sourceOrderNo, entity.internalStatus, entity.sourceStatus, entity.sourcePayloadHash);
        outboxStore.append(new OutboxMessage(
                UUID.randomUUID(),
                entity.tenantId,
                "ORDER",
                entity.id,
                eventType,
                1,
                writeJson(payload),
                OffsetDateTime.ofInstant(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)));
    }

    private InternalOrderEntity toEntity(Order order, String payloadHash, InternalOrderEntity existing,
                                          LocalDateTime now) {
        InternalOrderEntity entity = new InternalOrderEntity();
        entity.id = existing == null ? UUID.randomUUID().toString() : existing.id;
        entity.tenantId = order.tenantId();
        entity.orderNo = existing == null ? order.orderNo() : existing.orderNo;
        entity.sourceSystem = order.sourceSystem();
        entity.sourceOrderNo = order.sourceOrderNo();
        entity.internalStatus = existing == null ? order.internalStatus() : existing.internalStatus;
        entity.sourceStatus = order.sourceStatus();
        entity.paymentStatus = order.paymentStatus();
        entity.orderType = order.orderType();
        entity.totalAmount = order.totalAmount();
        entity.orderedAt = order.orderedAt();
        entity.sourceUpdatedAt = order.sourceUpdatedAt();
        entity.sourceUpdateTime = order.sourceUpdateTime();
        entity.deliveryDate = order.deliveryDate();
        entity.remark = order.remark();
        entity.sourceCustomerNo = order.sourceCustomerNo();
        entity.sourceCustomerGuid = order.sourceCustomerGuid();
        entity.customerName = order.customerName();
        entity.receiverName = order.receiverName();
        entity.receiverCompany = order.receiverCompany();
        entity.receiverPhone = order.receiverPhone();
        entity.receiverAddress = order.receiverAddress();
        entity.province = order.province();
        entity.city = order.city();
        entity.district = order.district();
        entity.sourceApiStatus = order.sourceApiStatus();
        entity.sourceExceptionStatus = order.sourceExceptionStatus();
        entity.sourceSendType = order.sourceSendType();
        entity.sourceLastOrderAt = order.sourceLastOrderAt();
        entity.sourceDevice = order.sourceDevice();
        entity.sourceAdminOrder = order.sourceAdminOrder();
        entity.splitType = order.splitType();
        entity.splitTypeName = order.splitTypeName();
        entity.sourcePayloadHash = payloadHash;
        entity.detailSyncedAt = order.detailSyncedAt() == null && existing != null
                ? existing.detailSyncedAt : order.detailSyncedAt();
        entity.importedAt = existing == null ? now : existing.importedAt;
        entity.syncedAt = now;
        entity.version = existing == null || existing.version == null ? 0 : existing.version + 1;
        entity.createdAt = existing == null ? now : existing.createdAt;
        entity.updatedAt = now;
        return entity;
    }

    private InternalOrderEntity findEntity(String tenantId, String sourceOrderNo) {
        return orderMapper.selectOne(Wrappers.<InternalOrderEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", Order.SOURCE_DINGHUOBAO)
                .eq("source_order_no", sourceOrderNo)
                .last("LIMIT 1"));
    }

    private static void appendFilters(QueryWrapper<InternalOrderEntity> query, OrderFilter filter) {
        if (filter.excludeDemoData()) {
            query.and(wrapper -> wrapper.isNull("source_device").or().ne("source_device", "demo-portal"));
        }
        if (filter.sourceStatus() != null && !filter.sourceStatus().isBlank()) {
            query.in("source_status", sourceStatusValues(filter.sourceStatus()));
        }
        if (filter.startTime() != null) query.ge("ordered_at", filter.startTime());
        if (filter.endTime() != null) query.le("ordered_at", filter.endTime());
        if (filter.sourceUpdatedFrom() != null) query.ge("source_updated_at", filter.sourceUpdatedFrom());
        if (filter.sourceUpdatedTo() != null) query.le("source_updated_at", filter.sourceUpdatedTo());
        if (filter.exceptionStatus() != null && !filter.exceptionStatus().isBlank()
                && !"all".equalsIgnoreCase(filter.exceptionStatus())) {
            query.eq("source_exception_status", filter.exceptionStatus());
        }
        if (filter.apiStatus() != null && !filter.apiStatus().isBlank()
                && !"all".equalsIgnoreCase(filter.apiStatus())) {
            query.eq("source_api_status", filter.apiStatus());
        }
        if (filter.paymentStatus() != null && !filter.paymentStatus().isBlank()) {
            query.eq("payment_status", filter.paymentStatus());
        }
        if (filter.splitType() != null) query.eq("split_type", filter.splitType().toString());
    }

    /**
     * 官方 getOrderList 请求参数使用 stock_up，列表返回/历史落库可能使用 stockup；
     * 两者都属于待出库，查询时展开别名避免 Portal 按官方参数查询时漏单。
     */
    static List<String> sourceStatusValues(String sourceStatus) {
        List<String> values = new ArrayList<>();
        for (String raw : sourceStatus.split(",")) {
            String value = raw.trim();
            if (value.isBlank()) continue;
            if ("stock_up".equals(value) || "stockup".equals(value)) {
                if (!values.contains("stockup")) values.add("stockup");
                if (!values.contains("stock_up")) values.add("stock_up");
            } else if (!values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String writeJson(Object payload) {
        try { return objectMapper.writeValueAsString(payload); }
        catch (RuntimeException error) { throw new IllegalStateException("订单事件序列化失败", error); }
    }

    private static Order toDomain(InternalOrderEntity entity) {
        return new Order(entity.id, entity.tenantId, entity.orderNo, entity.sourceSystem, entity.sourceOrderNo,
                entity.internalStatus, entity.sourceStatus, entity.paymentStatus, entity.orderType, entity.totalAmount,
                entity.orderedAt, entity.sourceUpdatedAt, entity.sourceUpdateTime, entity.deliveryDate, entity.remark, entity.sourceCustomerNo,
                entity.sourceCustomerGuid, entity.customerName, entity.receiverName, entity.receiverCompany,
                entity.receiverPhone, entity.receiverAddress, entity.province, entity.city, entity.district,
                entity.sourceApiStatus, entity.sourceExceptionStatus, entity.sourceSendType, entity.sourceLastOrderAt,
                entity.sourceDevice, entity.sourceAdminOrder, entity.splitType, entity.splitTypeName,
                entity.sourcePayloadHash, entity.detailSyncedAt, entity.importedAt, entity.syncedAt);
    }

    private static OrderLine toDomain(InternalOrderLineEntity entity) {
        return new OrderLine(entity.id, entity.sourceLineId, entity.sourceProductGuid, entity.skuNo,
                entity.sourceOptionsGoodsNo, entity.sourceBarcode,
                entity.productName, entity.productCode, entity.specificationFirst, entity.specificationSecond,
                entity.specificationName, entity.unitPrice, entity.quantity, entity.lineAmount, entity.unit, entity.remark);
    }

    private static OrderShipment toDomain(InternalOrderShipmentEntity entity) {
        return new OrderShipment(entity.id, entity.sourceShipmentNo, entity.status, entity.shipmentDate, entity.stockUpTime);
    }

}
