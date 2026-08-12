package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.application.port.out.OrderDocumentRepository;
import com.rigour.order.domain.model.order.DhbOrderDocuments.FinancialDocument;
import com.rigour.order.domain.model.order.DhbOrderDocuments.ReturnDetail;
import com.rigour.order.domain.model.order.DhbOrderDocuments.ReturnDocument;
import com.rigour.order.domain.model.order.DhbOrderDocuments.ReturnLine;
import com.rigour.order.domain.model.order.DhbOrderDocuments.Shipment;
import com.rigour.order.domain.model.order.DhbOrderDocuments.ShipmentDetail;
import com.rigour.order.domain.model.order.DhbOrderDocuments.ShipmentLine;
import com.rigour.order.infrastructure.persistence.entity.DhbFinancialDocumentEntity;
import com.rigour.order.infrastructure.persistence.entity.DhbReturnEntity;
import com.rigour.order.infrastructure.persistence.entity.DhbReturnLineEntity;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentEntity;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentLineEntity;
import com.rigour.order.infrastructure.persistence.mapper.DhbFinancialDocumentMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbReturnLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbReturnMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbShipmentLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.DhbShipmentMapper;
import com.rigour.shared.outbox.OutboxMessage;
import com.rigour.shared.outbox.OutboxStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 订货宝订单域附属单据的 MyBatis-Plus 仓储。
 *
 * <p>所有业务查询都以 {@code tenant_id + source_system} 隔离；导入使用来源业务键定位记录，
 * 再以 {@code payload_hash} 判断内容是否变化。只有明确携带详情时才替换明细，避免列表同步清空已有详情。</p>
 */
@Repository
public class MybatisPlusOrderDocumentRepository implements OrderDocumentRepository {
    private static final String SOURCE_SYSTEM = "DINGHUOBAO";

    private final DhbShipmentMapper shipmentMapper;
    private final DhbShipmentLineMapper shipmentLineMapper;
    private final DhbReturnMapper returnMapper;
    private final DhbReturnLineMapper returnLineMapper;
    private final DhbFinancialDocumentMapper financialMapper;
    private final OutboxStore outboxStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MybatisPlusOrderDocumentRepository(
            DhbShipmentMapper shipmentMapper,
            DhbShipmentLineMapper shipmentLineMapper,
            DhbReturnMapper returnMapper,
            DhbReturnLineMapper returnLineMapper,
            DhbFinancialDocumentMapper financialMapper,
            OutboxStore outboxStore,
            ObjectMapper objectMapper,
            Clock clock) {
        this.shipmentMapper = shipmentMapper;
        this.shipmentLineMapper = shipmentLineMapper;
        this.returnMapper = returnMapper;
        this.returnLineMapper = returnLineMapper;
        this.financialMapper = financialMapper;
        this.outboxStore = outboxStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等保存发货单；唯一业务键为 tenantId + DINGHUOBAO + shipmentNo。
     *
     * @return 新增、来源内容变化或首次补齐详情的发货单数量
     */
    @Override
    @Transactional
    public int importShipments(String tenantId, List<DhbOrderImportBatch.ShipmentItem> items) {
        int changedCount = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (DhbOrderImportBatch.ShipmentItem item : items) {
            requireText(item.shipmentNo(), "shipmentNo");
            requireText(item.payloadHash(), "payloadHash");
            DhbShipmentEntity existing = findShipmentEntity(tenantId, item.shipmentNo());
            boolean changed = existing == null
                    || !Objects.equals(existing.payloadHash, item.payloadHash())
                    || item.detailIncluded() && shipmentDetailsIncomplete(existing, item.lines().size());
            DhbShipmentEntity entity = shipmentEntity(tenantId, item, existing, now);
            if (existing == null) shipmentMapper.insert(entity);
            else if (changed) shipmentMapper.updateById(entity);
            if (item.detailIncluded() && changed) replaceShipmentLines(entity.id, item.lines(), now);
            if (changed) {
                appendDocumentEvent(entity.tenantId, "DHB_SHIPMENT", entity.id,
                        existing == null ? "DINGHUOBAO_SHIPMENT_IMPORTED" : "DINGHUOBAO_SHIPMENT_SOURCE_UPDATED",
                        new SourceDocumentChangedEvent(entity.id, SOURCE_SYSTEM, "SHIPMENT", entity.shipmentNo,
                                entity.sourceStatus, entity.payloadHash), now);
                changedCount++;
            }
        }
        return changedCount;
    }

    /**
     * 幂等保存退货单；唯一业务键为 tenantId + DINGHUOBAO + returnNo。
     *
     * @return 新增、来源内容变化或首次补齐详情的退货单数量
     */
    @Override
    @Transactional
    public int importReturns(String tenantId, List<DhbOrderImportBatch.ReturnItem> items) {
        int changedCount = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (DhbOrderImportBatch.ReturnItem item : items) {
            requireText(item.returnNo(), "returnNo");
            requireText(item.payloadHash(), "payloadHash");
            DhbReturnEntity existing = findReturnEntity(tenantId, item.returnNo());
            boolean changed = existing == null
                    || !Objects.equals(existing.payloadHash, item.payloadHash())
                    || item.detailIncluded() && returnDetailsIncomplete(existing, item.lines().size());
            DhbReturnEntity entity = returnEntity(tenantId, item, existing, now);
            if (existing == null) returnMapper.insert(entity);
            else if (changed) returnMapper.updateById(entity);
            if (item.detailIncluded() && changed) replaceReturnLines(entity.id, item.lines(), now);
            if (changed) {
                appendDocumentEvent(entity.tenantId, "DHB_RETURN", entity.id,
                        existing == null ? "DINGHUOBAO_RETURN_IMPORTED" : "DINGHUOBAO_RETURN_SOURCE_UPDATED",
                        new SourceDocumentChangedEvent(entity.id, SOURCE_SYSTEM, "RETURN", entity.returnNo,
                                entity.sourceStatus, entity.payloadHash), now);
                changedCount++;
            }
        }
        return changedCount;
    }

    /** 来源摘要未变化时校验发货明细数量，避免 detail_available 因本地明细被删除而失真。 */
    private boolean shipmentDetailsIncomplete(DhbShipmentEntity existing, int expectedLineCount) {
        if (existing == null) return false;
        if (!Boolean.TRUE.equals(existing.detailAvailable)) return true;
        Long lineCount = shipmentLineMapper.selectCount(Wrappers.<DhbShipmentLineEntity>query()
                .eq("shipment_id", existing.id));
        return countOrZero(lineCount) != expectedLineCount;
    }

    /** 来源摘要未变化时校验退货明细数量，避免 detail_available 因本地明细被删除而失真。 */
    private boolean returnDetailsIncomplete(DhbReturnEntity existing, int expectedLineCount) {
        if (existing == null) return false;
        if (!Boolean.TRUE.equals(existing.detailAvailable)) return true;
        Long lineCount = returnLineMapper.selectCount(Wrappers.<DhbReturnLineEntity>query()
                .eq("return_id", existing.id));
        return countOrZero(lineCount) != expectedLineCount;
    }

    private static long countOrZero(Long count) {
        return count == null ? 0L : count;
    }

    /**
     * 幂等保存收款或付款单；唯一业务键为 tenantId + DINGHUOBAO + documentType + documentNo。
     *
     * @return 新增或来源内容发生变化的收付款单数量
     */
    @Override
    @Transactional
    public int importFinancialDocuments(String tenantId, List<DhbOrderImportBatch.FinancialItem> items) {
        int changedCount = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (DhbOrderImportBatch.FinancialItem item : items) {
            requireText(item.documentType(), "documentType");
            requireText(item.documentNo(), "documentNo");
            requireText(item.payloadHash(), "payloadHash");
            DhbFinancialDocumentEntity existing = findFinancialEntity(
                    tenantId, item.documentType(), item.documentNo());
            boolean changed = existing == null || !Objects.equals(existing.payloadHash, item.payloadHash());
            DhbFinancialDocumentEntity entity = financialEntity(tenantId, item, existing, now);
            if (existing == null) financialMapper.insert(entity);
            else if (changed) financialMapper.updateById(entity);
            if (changed) {
                String eventPrefix = "DINGHUOBAO_" + entity.documentType;
                appendDocumentEvent(entity.tenantId, "DHB_FINANCIAL_DOCUMENT", entity.id,
                        eventPrefix + (existing == null ? "_IMPORTED" : "_SOURCE_UPDATED"),
                        new SourceDocumentChangedEvent(entity.id, SOURCE_SYSTEM, entity.documentType,
                                entity.documentNo, entity.sourceStatus, entity.payloadHash), now);
                changedCount++;
            }
        }
        return changedCount;
    }

    /** 查询当前租户的本地发货单分页，不实时访问订货宝。 */
    @Override
    public List<Shipment> findShipments(String tenantId, DocumentFilter filter) {
        QueryWrapper<DhbShipmentEntity> query = shipmentQuery(tenantId, filter);
        query.orderByDesc("shipment_at").orderByDesc("id")
                .last("LIMIT " + filter.step() + " OFFSET " + filter.begin());
        return shipmentMapper.selectList(query).stream().map(MybatisPlusOrderDocumentRepository::shipment).toList();
    }

    /** 统计当前租户符合条件的本地发货单。 */
    @Override
    public long countShipments(String tenantId, DocumentFilter filter) {
        Long count = shipmentMapper.selectCount(shipmentQuery(tenantId, filter));
        return count == null ? 0 : count;
    }

    /** 按发货单号查询聚合详情；未落库时返回 null。 */
    @Override
    public ShipmentDetail findShipment(String tenantId, String shipmentNo) {
        DhbShipmentEntity entity = findShipmentEntity(tenantId, shipmentNo);
        if (entity == null) return null;
        List<ShipmentLine> lines = shipmentLineMapper.selectList(Wrappers.<DhbShipmentLineEntity>query()
                        .eq("shipment_id", entity.id).orderByAsc("source_line_id"))
                .stream().map(MybatisPlusOrderDocumentRepository::shipmentLine).toList();
        return new ShipmentDetail(shipment(entity), lines);
    }

    /** 查询当前租户的本地退货单分页，不实时访问订货宝。 */
    @Override
    public List<ReturnDocument> findReturns(String tenantId, DocumentFilter filter) {
        QueryWrapper<DhbReturnEntity> query = returnQuery(tenantId, filter);
        query.orderByDesc("returned_at").orderByDesc("id")
                .last("LIMIT " + filter.step() + " OFFSET " + filter.begin());
        return returnMapper.selectList(query).stream().map(MybatisPlusOrderDocumentRepository::returnDocument).toList();
    }

    /** 统计当前租户符合条件的本地退货单。 */
    @Override
    public long countReturns(String tenantId, DocumentFilter filter) {
        Long count = returnMapper.selectCount(returnQuery(tenantId, filter));
        return count == null ? 0 : count;
    }

    /** 按退货单号查询聚合详情；未落库时返回 null。 */
    @Override
    public ReturnDetail findReturn(String tenantId, String returnNo) {
        DhbReturnEntity entity = findReturnEntity(tenantId, returnNo);
        if (entity == null) return null;
        List<ReturnLine> lines = returnLineMapper.selectList(Wrappers.<DhbReturnLineEntity>query()
                        .eq("return_id", entity.id).orderByAsc("source_line_id"))
                .stream().map(MybatisPlusOrderDocumentRepository::returnLine).toList();
        return new ReturnDetail(returnDocument(entity), lines);
    }

    /** 查询指定类型的本地收付款单；documentType 为 RECEIPT 或 PAYMENT。 */
    @Override
    public List<FinancialDocument> findFinancialDocuments(
            String tenantId, String documentType, DocumentFilter filter) {
        QueryWrapper<DhbFinancialDocumentEntity> query = financialQuery(tenantId, documentType, filter);
        query.orderByDesc("transaction_at").orderByDesc("id")
                .last("LIMIT " + filter.step() + " OFFSET " + filter.begin());
        return financialMapper.selectList(query).stream()
                .map(MybatisPlusOrderDocumentRepository::financialDocument).toList();
    }

    /** 统计指定类型、当前租户符合条件的本地收付款单。 */
    @Override
    public long countFinancialDocuments(String tenantId, String documentType, DocumentFilter filter) {
        Long count = financialMapper.selectCount(financialQuery(tenantId, documentType, filter));
        return count == null ? 0 : count;
    }

    private void replaceShipmentLines(String shipmentId, List<DhbOrderImportBatch.ShipmentLineItem> lines,
                                      LocalDateTime now) {
        shipmentLineMapper.delete(Wrappers.<DhbShipmentLineEntity>query().eq("shipment_id", shipmentId));
        for (DhbOrderImportBatch.ShipmentLineItem item : lines) {
            requireText(item.sourceLineId(), "shipment.sourceLineId");
            DhbShipmentLineEntity entity = new DhbShipmentLineEntity();
            entity.id = UUID.randomUUID().toString();
            entity.shipmentId = shipmentId;
            entity.sourceLineId = item.sourceLineId();
            entity.sourceProductGuid = item.sourceProductGuid();
            entity.skuNo = item.skuNo();
            entity.productCode = item.productCode();
            entity.productName = item.productName();
            entity.quantity = item.quantity();
            entity.unitPrice = item.unitPrice();
            entity.lineAmount = item.amount();
            entity.unitName = item.unit();
            entity.warehouseNo = item.warehouseNo();
            entity.remark = item.remark();
            entity.createdAt = now;
            entity.updatedAt = now;
            shipmentLineMapper.insert(entity);
        }
    }

    private void replaceReturnLines(String returnId, List<DhbOrderImportBatch.ReturnLineItem> lines,
                                    LocalDateTime now) {
        returnLineMapper.delete(Wrappers.<DhbReturnLineEntity>query().eq("return_id", returnId));
        for (DhbOrderImportBatch.ReturnLineItem item : lines) {
            requireText(item.sourceLineId(), "return.sourceLineId");
            DhbReturnLineEntity entity = new DhbReturnLineEntity();
            entity.id = UUID.randomUUID().toString();
            entity.returnId = returnId;
            entity.sourceLineId = item.sourceLineId();
            entity.sourceProductGuid = item.sourceProductGuid();
            entity.skuNo = item.skuNo();
            entity.productCode = item.productCode();
            entity.productName = item.productName();
            entity.quantity = item.quantity();
            entity.confirmedQuantity = item.confirmedQuantity();
            entity.unitPrice = item.unitPrice();
            entity.confirmedPrice = item.confirmedPrice();
            entity.unitName = item.unit();
            entity.warehouseNo = item.warehouseNo();
            entity.warehouseName = item.warehouseName();
            entity.remark = item.remark();
            entity.createdAt = now;
            entity.updatedAt = now;
            returnLineMapper.insert(entity);
        }
    }

    private DhbShipmentEntity findShipmentEntity(String tenantId, String shipmentNo) {
        return shipmentMapper.selectOne(Wrappers.<DhbShipmentEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("shipment_no", shipmentNo).last("LIMIT 1"));
    }

    private DhbReturnEntity findReturnEntity(String tenantId, String returnNo) {
        return returnMapper.selectOne(Wrappers.<DhbReturnEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("return_no", returnNo).last("LIMIT 1"));
    }

    private DhbFinancialDocumentEntity findFinancialEntity(
            String tenantId, String documentType, String documentNo) {
        return financialMapper.selectOne(Wrappers.<DhbFinancialDocumentEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("document_type", documentType).eq("document_no", documentNo).last("LIMIT 1"));
    }

    private static QueryWrapper<DhbShipmentEntity> shipmentQuery(String tenantId, DocumentFilter filter) {
        QueryWrapper<DhbShipmentEntity> query = Wrappers.query();
        query.eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM);
        appendDocumentFilters(query, filter, "shipment_at");
        return query;
    }

    private static QueryWrapper<DhbReturnEntity> returnQuery(String tenantId, DocumentFilter filter) {
        QueryWrapper<DhbReturnEntity> query = Wrappers.query();
        query.eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM);
        appendDocumentFilters(query, filter, "returned_at");
        return query;
    }

    private static QueryWrapper<DhbFinancialDocumentEntity> financialQuery(
            String tenantId, String documentType, DocumentFilter filter) {
        QueryWrapper<DhbFinancialDocumentEntity> query = Wrappers.query();
        query.eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM).eq("document_type", documentType);
        appendDocumentFilters(query, filter, "transaction_at");
        return query;
    }

    private static <T> void appendDocumentFilters(
            QueryWrapper<T> query, DocumentFilter filter, String businessTimeColumn) {
        if (filter.status() != null) query.eq("source_status", filter.status());
        if (filter.typeId() != null) {
            query.in("source_type_id", java.util.Arrays.stream(filter.typeId().split(","))
                    .map(String::strip).filter(value -> !value.isBlank()).toList());
        }
        if (filter.orderNo() != null) query.eq("order_no", filter.orderNo());
        if (filter.from() != null) query.ge(businessTimeColumn, filter.from());
        if (filter.to() != null) query.le(businessTimeColumn, filter.to());
    }

    private static DhbShipmentEntity shipmentEntity(
            String tenantId, DhbOrderImportBatch.ShipmentItem item, DhbShipmentEntity existing,
            LocalDateTime now) {
        DhbShipmentEntity entity = new DhbShipmentEntity();
        entity.id = existing == null ? UUID.randomUUID().toString() : existing.id;
        entity.tenantId = tenantId;
        entity.sourceSystem = SOURCE_SYSTEM;
        entity.sourceShipmentId = first(item.sourceShipmentId(), existing == null ? null : existing.sourceShipmentId);
        entity.shipmentNo = item.shipmentNo();
        entity.orderNo = first(item.orderNo(), existing == null ? null : existing.orderNo);
        entity.sourceStatus = first(item.status(), existing == null ? null : existing.sourceStatus);
        entity.sourceStatusName = first(item.statusName(), existing == null ? null : existing.sourceStatusName);
        entity.sourceTypeId = first(item.typeId(), existing == null ? null : existing.sourceTypeId);
        entity.sourceTypeName = first(item.typeName(), existing == null ? null : existing.sourceTypeName);
        entity.customerNo = first(item.customerNo(), existing == null ? null : existing.customerNo);
        entity.customerName = first(item.customerName(), existing == null ? null : existing.customerName);
        entity.customerGuid = first(item.customerGuid(), existing == null ? null : existing.customerGuid);
        entity.warehouseNo = first(item.warehouseNo(), existing == null ? null : existing.warehouseNo);
        entity.warehouseName = first(item.warehouseName(), existing == null ? null : existing.warehouseName);
        entity.warehouseGuid = first(item.warehouseGuid(), existing == null ? null : existing.warehouseGuid);
        entity.shipmentAt = item.shipmentAt() == null
                ? existing == null ? null : existing.shipmentAt : utc(item.shipmentAt());
        entity.logisticsName = first(item.logisticsName(), existing == null ? null : existing.logisticsName);
        entity.trackingNo = first(item.trackingNo(), existing == null ? null : existing.trackingNo);
        entity.remark = first(item.remark(), existing == null ? null : existing.remark);
        entity.sourceCreatedAt = item.createdAt() == null
                ? existing == null ? null : existing.sourceCreatedAt : utc(item.createdAt());
        entity.sourceUpdatedAt = item.updatedAt() == null
                ? existing == null ? null : existing.sourceUpdatedAt : utc(item.updatedAt());
        entity.rawJson = defaultJson(item.rawJson());
        entity.payloadHash = item.payloadHash();
        entity.detailAvailable = item.detailIncluded()
                || existing != null && Boolean.TRUE.equals(existing.detailAvailable);
        entity.syncedAt = now;
        entity.createdAt = existing == null ? now : existing.createdAt;
        entity.updatedAt = now;
        return entity;
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static DhbReturnEntity returnEntity(
            String tenantId, DhbOrderImportBatch.ReturnItem item, DhbReturnEntity existing,
            LocalDateTime now) {
        DhbReturnEntity entity = new DhbReturnEntity();
        entity.id = existing == null ? UUID.randomUUID().toString() : existing.id;
        entity.tenantId = tenantId;
        entity.sourceSystem = SOURCE_SYSTEM;
        entity.returnNo = item.returnNo();
        entity.orderNo = item.orderNo();
        entity.sourceStatus = item.status();
        entity.staffName = item.staffName();
        entity.returnAmount = item.returnAmount();
        entity.settlementAmount = item.settlementAmount();
        entity.returnedAt = utc(item.returnedAt());
        entity.sourceUpdatedAt = utc(item.updatedAt());
        entity.reason = item.reason();
        entity.customerNo = item.customerNo();
        entity.customerGuid = item.customerGuid();
        entity.consignee = item.consignee();
        entity.phone = item.phone();
        entity.address = item.address();
        entity.logisticsCompany = item.logisticsCompany();
        entity.logisticsNo = item.logisticsNo();
        entity.returnType = item.returnType();
        entity.deliveryMode = item.deliveryMode();
        entity.rawJson = defaultJson(item.rawJson());
        entity.payloadHash = item.payloadHash();
        entity.detailAvailable = item.detailIncluded()
                || existing != null && Boolean.TRUE.equals(existing.detailAvailable);
        entity.syncedAt = now;
        entity.createdAt = existing == null ? now : existing.createdAt;
        entity.updatedAt = now;
        return entity;
    }

    private static DhbFinancialDocumentEntity financialEntity(
            String tenantId, DhbOrderImportBatch.FinancialItem item,
            DhbFinancialDocumentEntity existing, LocalDateTime now) {
        DhbFinancialDocumentEntity entity = new DhbFinancialDocumentEntity();
        entity.id = existing == null ? UUID.randomUUID().toString() : existing.id;
        entity.tenantId = tenantId;
        entity.sourceSystem = SOURCE_SYSTEM;
        entity.documentType = item.documentType();
        entity.documentNo = item.documentNo();
        entity.relatedDocumentNo = item.relatedDocumentNo();
        entity.orderNo = item.orderNo();
        entity.customerNo = item.customerNo();
        entity.customerGuid = item.customerGuid();
        entity.businessType = item.businessType();
        entity.paymentMethod = item.paymentMethod();
        entity.amount = item.amount();
        entity.sourceStatus = item.status();
        entity.transactionAt = utc(item.transactionAt());
        entity.sourceCreatedAt = utc(item.createdAt());
        entity.sourceUpdatedAt = utc(item.updatedAt());
        entity.serialNumber = item.serialNumber();
        entity.accountName = item.accountName();
        entity.bankName = item.bankName();
        entity.accountNumber = item.accountNumber();
        entity.remark = item.remark();
        entity.rawJson = defaultJson(item.rawJson());
        entity.payloadHash = item.payloadHash();
        entity.syncedAt = now;
        entity.createdAt = existing == null ? now : existing.createdAt;
        entity.updatedAt = now;
        return entity;
    }

    private static Shipment shipment(DhbShipmentEntity entity) {
        return new Shipment(entity.id, entity.tenantId, entity.sourceShipmentId, entity.shipmentNo,
                entity.orderNo, entity.sourceStatus, entity.sourceStatusName, entity.sourceTypeId,
                entity.sourceTypeName, entity.customerNo, entity.customerName, entity.customerGuid,
                entity.warehouseNo, entity.warehouseName, entity.warehouseGuid, entity.shipmentAt,
                entity.logisticsName, entity.trackingNo, entity.remark, entity.sourceCreatedAt,
                entity.sourceUpdatedAt, entity.payloadHash, Boolean.TRUE.equals(entity.detailAvailable),
                entity.syncedAt);
    }

    private static ShipmentLine shipmentLine(DhbShipmentLineEntity entity) {
        return new ShipmentLine(entity.id, entity.sourceLineId, entity.sourceProductGuid, entity.skuNo,
                entity.productCode, entity.productName, entity.quantity, entity.unitPrice, entity.lineAmount,
                entity.unitName, entity.warehouseNo, entity.remark);
    }

    private static ReturnDocument returnDocument(DhbReturnEntity entity) {
        return new ReturnDocument(entity.id, entity.tenantId, entity.returnNo, entity.orderNo,
                entity.sourceStatus, entity.staffName, entity.returnAmount, entity.settlementAmount,
                entity.returnedAt, entity.sourceUpdatedAt, entity.reason, entity.customerNo,
                entity.customerGuid, entity.consignee, entity.phone, entity.address, entity.logisticsCompany,
                entity.logisticsNo, entity.returnType, entity.deliveryMode, entity.payloadHash,
                Boolean.TRUE.equals(entity.detailAvailable), entity.syncedAt);
    }

    private static ReturnLine returnLine(DhbReturnLineEntity entity) {
        return new ReturnLine(entity.id, entity.sourceLineId, entity.sourceProductGuid, entity.skuNo,
                entity.productCode, entity.productName, entity.quantity, entity.confirmedQuantity,
                entity.unitPrice, entity.confirmedPrice, entity.unitName, entity.warehouseNo,
                entity.warehouseName, entity.remark);
    }

    private static FinancialDocument financialDocument(DhbFinancialDocumentEntity entity) {
        return new FinancialDocument(entity.id, entity.tenantId, entity.documentType, entity.documentNo,
                entity.relatedDocumentNo, entity.orderNo, entity.customerNo, entity.customerGuid,
                entity.businessType, entity.paymentMethod, entity.amount, entity.sourceStatus,
                entity.transactionAt, entity.sourceCreatedAt, entity.sourceUpdatedAt, entity.serialNumber,
                entity.accountName, entity.bankName, entity.accountNumber, entity.remark,
                entity.payloadHash, entity.syncedAt);
    }

    private static LocalDateTime utc(java.time.Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private void appendDocumentEvent(String tenantId, String aggregateType, String aggregateId,
                                     String eventType, SourceDocumentChangedEvent payload,
                                     LocalDateTime occurredAt) {
        outboxStore.append(new OutboxMessage(UUID.randomUUID(), tenantId, aggregateType, aggregateId,
                eventType, 1, writeJson(payload),
                OffsetDateTime.ofInstant(occurredAt.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订单附属单据事件序列化失败", exception);
        }
    }

    /** 不包含手机号、地址、银行账号等敏感字段的领域事件载荷。 */
    private record SourceDocumentChangedEvent(String id, String sourceSystem, String documentType,
                                              String documentNo, String sourceStatus, String payloadHash) {
    }

    private static String defaultJson(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    }
}
