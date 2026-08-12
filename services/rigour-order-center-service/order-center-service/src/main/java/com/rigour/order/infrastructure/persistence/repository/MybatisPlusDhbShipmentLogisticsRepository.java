package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.application.port.out.DhbShipmentLogisticsRepository;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentLogisticsEntity;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentLogisticsLineEntity;
import com.rigour.order.infrastructure.persistence.mapper.DhbShipmentLogisticsLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbShipmentLogisticsMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 使用MyBatis-Plus保存订货宝getWaitShips物流快照和明细。 */
@Repository
public class MybatisPlusDhbShipmentLogisticsRepository implements DhbShipmentLogisticsRepository {
    private static final String SOURCE_SYSTEM = "DINGHUOBAO";

    private final DhbShipmentLogisticsMapper mapper;
    private final DhbShipmentLogisticsLineMapper lineMapper;
    private final Clock clock;

    public MybatisPlusDhbShipmentLogisticsRepository(DhbShipmentLogisticsMapper mapper,
                                                      DhbShipmentLogisticsLineMapper lineMapper,
                                                      Clock clock) {
        this.mapper = mapper;
        this.lineMapper = lineMapper;
        this.clock = clock;
    }

    /** 按租户、来源和订单号幂等保存；原始内容不变时不替换明细。 */
    @Override
    @Transactional
    public int importSnapshots(String tenantId, List<DhbOrderImportBatch.ShipmentLogisticsItem> items) {
        requireText(tenantId, "tenantId");
        int changedCount = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (DhbOrderImportBatch.ShipmentLogisticsItem item : items == null ? List.<DhbOrderImportBatch.ShipmentLogisticsItem>of() : items) {
            requireText(item.orderNo(), "shipmentLogistics.orderNo");
            requireText(item.payloadHash(), "shipmentLogistics.payloadHash");
            DhbShipmentLogisticsEntity existing = findEntity(tenantId, item.orderNo());
            boolean changed = existing == null
                    || !Objects.equals(existing.payloadHash, item.payloadHash())
                    || logisticsDetailsIncomplete(existing, expectedLineCount(item));
            DhbShipmentLogisticsEntity entity = toEntity(tenantId, item, existing, now);
            if (changed) entity.updatedAt = now;
            if (existing == null) mapper.insert(entity);
            if (changed) {
                if (existing != null) mapper.updateById(entity);
                replaceLines(entity.id, item, now);
                changedCount++;
            }
        }
        return changedCount;
    }

    /** 来源摘要未变化时校验发货和待出库明细总行数，修复本地部分明细缺失。 */
    private boolean logisticsDetailsIncomplete(DhbShipmentLogisticsEntity existing, int expectedLineCount) {
        if (existing == null) return false;
        Long lineCount = lineMapper.selectCount(Wrappers.<DhbShipmentLogisticsLineEntity>query()
                .eq("logistics_id", existing.id));
        return (lineCount == null ? 0L : lineCount) != expectedLineCount;
    }

    private static int expectedLineCount(DhbOrderImportBatch.ShipmentLogisticsItem item) {
        int count = item.waitStock().size();
        for (DhbOrderImportBatch.ShipmentLogisticsRecord shipment : item.shipped()) {
            count += shipment.lines().size();
        }
        return count;
    }

    @Override
    public List<Snapshot> findPage(String tenantId, Query query) {
        QueryWrapper<DhbShipmentLogisticsEntity> wrapper = query(tenantId, query);
        wrapper.orderByDesc("updated_at").orderByDesc("id")
                .last("LIMIT " + query.step() + " OFFSET " + query.begin());
        return mapper.selectList(wrapper).stream().map(MybatisPlusDhbShipmentLogisticsRepository::snapshot).toList();
    }

    @Override
    public long count(String tenantId, Query query) {
        Long count = mapper.selectCount(query(tenantId, query));
        return count == null ? 0 : count;
    }

    @Override
    public Detail findDetail(String tenantId, String orderNo) {
        DhbShipmentLogisticsEntity entity = findEntity(tenantId, orderNo);
        if (entity == null) return null;
        List<Line> lines = lineMapper.selectList(Wrappers.<DhbShipmentLogisticsLineEntity>query()
                        .eq("logistics_id", entity.id).orderByAsc("line_type").orderByAsc("source_line_id"))
                .stream().map(MybatisPlusDhbShipmentLogisticsRepository::line).toList();
        return new Detail(snapshot(entity), lines);
    }

    private DhbShipmentLogisticsEntity findEntity(String tenantId, String orderNo) {
        return mapper.selectOne(Wrappers.<DhbShipmentLogisticsEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("order_no", orderNo).last("LIMIT 1"));
    }

    private static QueryWrapper<DhbShipmentLogisticsEntity> query(String tenantId, Query filter) {
        QueryWrapper<DhbShipmentLogisticsEntity> wrapper = Wrappers.query();
        wrapper.eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM);
        if (filter.status() != null && !filter.status().isBlank()) {
            wrapper.eq("source_status", filter.status());
        }
        if (filter.orderNo() != null && !filter.orderNo().isBlank()) {
            wrapper.eq("order_no", filter.orderNo());
        }
        if (filter.from() != null) wrapper.ge("shipment_at", filter.from());
        if (filter.to() != null) wrapper.le("shipment_at", filter.to());
        return wrapper;
    }

    private static DhbShipmentLogisticsEntity toEntity(
            String tenantId, DhbOrderImportBatch.ShipmentLogisticsItem item,
            DhbShipmentLogisticsEntity existing, LocalDateTime now) {
        DhbShipmentLogisticsEntity entity = new DhbShipmentLogisticsEntity();
        entity.id = existing == null ? UUID.randomUUID().toString() : existing.id;
        entity.tenantId = tenantId;
        entity.sourceSystem = SOURCE_SYSTEM;
        entity.orderNo = item.orderNo();
        DhbOrderImportBatch.ShipmentLogisticsRecord latest = latest(item.shipped());
        entity.shipmentNo = latest == null ? null : latest.shipmentNo();
        entity.sourceStatus = latest == null ? null : latest.status();
        entity.logisticsName = latest == null ? null : latest.logisticsName();
        entity.logisticsCode = latest == null ? null : latest.logisticsCode();
        entity.trackingNo = latest == null ? null : latest.trackingNo();
        entity.shipmentAt = utc(latest == null ? null : latest.shipmentAt());
        entity.stockUpAt = utc(latest == null ? null : latest.stockUpAt());
        entity.warehouseNo = latest == null ? null : latest.warehouseNo();
        entity.warehouseName = latest == null ? null : latest.warehouseName();
        entity.shippedCount = item.shipped().size();
        entity.waitStockCount = item.waitStock().size();
        entity.rawJson = defaultJson(item.rawJson());
        entity.payloadHash = item.payloadHash();
        entity.syncedAt = now;
        entity.createdAt = existing == null ? now : existing.createdAt;
        entity.updatedAt = existing == null ? now : existing.updatedAt;
        return entity;
    }

    private void replaceLines(String logisticsId, DhbOrderImportBatch.ShipmentLogisticsItem item,
                               LocalDateTime now) {
        lineMapper.delete(Wrappers.<DhbShipmentLogisticsLineEntity>query().eq("logistics_id", logisticsId));
        for (DhbOrderImportBatch.ShipmentLogisticsRecord shipment : item.shipped()) {
            for (DhbOrderImportBatch.ShipmentLogisticsLineItem source : shipment.lines()) {
                lineMapper.insert(shippedLine(logisticsId, shipment, source, now));
            }
        }
        for (DhbOrderImportBatch.WaitStockItem source : item.waitStock()) {
            lineMapper.insert(waitStockLine(logisticsId, source, now));
        }
    }

    private static DhbShipmentLogisticsLineEntity shippedLine(
            String logisticsId, DhbOrderImportBatch.ShipmentLogisticsRecord shipment,
            DhbOrderImportBatch.ShipmentLogisticsLineItem source, LocalDateTime now) {
        DhbShipmentLogisticsLineEntity entity = new DhbShipmentLogisticsLineEntity();
        entity.id = UUID.randomUUID().toString();
        entity.logisticsId = logisticsId;
        entity.lineType = "SHIPPED";
        entity.shipmentNo = shipment.shipmentNo();
        entity.sourceLineId = requiredLineId(source.sourceLineId());
        entity.orderLineId = source.orderLineId();
        entity.productId = source.productId();
        entity.skuNo = source.skuNo();
        entity.listType = source.listType();
        entity.productCode = source.productCode();
        entity.productName = source.productName();
        entity.specification = source.specification();
        entity.unit = source.unit();
        entity.containerUnit = source.containerUnit();
        entity.conversionNumber = source.conversionNumber();
        entity.quantity = source.quantity();
        entity.warehouseNo = source.warehouseNo();
        entity.warehouseName = source.warehouseName();
        entity.remark = source.remark();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    private static DhbShipmentLogisticsLineEntity waitStockLine(
            String logisticsId, DhbOrderImportBatch.WaitStockItem source, LocalDateTime now) {
        DhbShipmentLogisticsLineEntity entity = new DhbShipmentLogisticsLineEntity();
        entity.id = UUID.randomUUID().toString();
        entity.logisticsId = logisticsId;
        entity.lineType = "WAIT_STOCK";
        entity.shipmentNo = "";
        entity.sourceLineId = requiredLineId(source.sourceLineId());
        entity.productId = source.productId();
        entity.skuNo = source.skuNo();
        entity.listType = source.listType();
        entity.productCode = source.productCode();
        entity.productName = source.productName();
        entity.specification = source.specification();
        entity.unit = source.unit();
        entity.containerUnit = source.containerUnit();
        entity.conversionNumber = source.conversionNumber();
        entity.orderedQuantity = source.orderedQuantity();
        entity.stockedQuantity = source.stockedQuantity();
        entity.realStock = source.realStock();
        entity.waitQuantity = source.waitQuantity();
        entity.warehouseNo = source.warehouseNo();
        entity.warehouseName = source.warehouseName();
        entity.remark = source.remark();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    private static Snapshot snapshot(DhbShipmentLogisticsEntity entity) {
        return new Snapshot(entity.orderNo, entity.shipmentNo, entity.sourceStatus,
                entity.logisticsName, entity.logisticsCode, entity.trackingNo,
                entity.shipmentAt, entity.stockUpAt, entity.warehouseNo, entity.warehouseName,
                value(entity.shippedCount), value(entity.waitStockCount), entity.syncedAt);
    }

    private static Line line(DhbShipmentLogisticsLineEntity entity) {
        return new Line(entity.lineType, entity.sourceLineId, entity.orderLineId, entity.productId,
                entity.skuNo, entity.productCode, entity.productName, entity.specification,
                entity.unit, entity.containerUnit, entity.conversionNumber, entity.quantity,
                entity.orderedQuantity, entity.stockedQuantity, entity.realStock, entity.waitQuantity,
                entity.warehouseNo, entity.warehouseName, entity.remark);
    }

    private static DhbOrderImportBatch.ShipmentLogisticsRecord latest(
            List<DhbOrderImportBatch.ShipmentLogisticsRecord> shipped) {
        return shipped == null || shipped.isEmpty() ? null : shipped.get(shipped.size() - 1);
    }

    private static LocalDateTime utc(java.time.Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static int value(Integer value) { return value == null ? 0 : value; }

    private static String defaultJson(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static String requiredLineId(String value) {
        requireText(value, "shipmentLogistics.sourceLineId");
        return value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    }
}
