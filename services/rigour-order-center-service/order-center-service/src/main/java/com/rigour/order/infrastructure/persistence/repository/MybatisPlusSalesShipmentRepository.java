package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.api.v1.model.SalesShipmentLineView;
import com.rigour.order.api.v1.model.SalesShipmentSummaryView;
import com.rigour.order.application.port.out.OrderSalesShipmentStore;
import com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentLineWrite;
import com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentWrite;
import com.rigour.order.domain.enums.SalesShipmentStatus;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesShipmentEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesShipmentLineEntity;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesOrderMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesShipmentLineMapper;
import com.rigour.order.infrastructure.persistence.mapper.InternalSalesShipmentMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 自研销售发货单仓储。 */
@Repository
public class MybatisPlusSalesShipmentRepository
        extends ServiceImpl<InternalSalesShipmentMapper, InternalSalesShipmentEntity>
        implements OrderSalesShipmentStore {
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final InternalSalesShipmentLineMapper lineMapper;
    private final InternalSalesOrderMapper orderMapper;
    private final Clock clock;

    public MybatisPlusSalesShipmentRepository(
            InternalSalesShipmentMapper mapper,
            InternalSalesShipmentLineMapper lineMapper,
            InternalSalesOrderMapper orderMapper,
            Clock orderClock) {
        this.baseMapper = mapper;
        this.lineMapper = lineMapper;
        this.orderMapper = orderMapper;
        this.clock = orderClock;
    }

    @Override
    public OrderPageView<SalesShipmentSummaryView> shipments(
            String tenantId, int begin, int step, SalesShipmentSearchCriteria criteria) {
        InternalSalesShipmentMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<SalesShipmentSummaryView> items = mapper.selectList(query(tenantId, criteria)
                        .orderByDesc(InternalSalesShipmentEntity::getShipTime)
                        .orderByDesc(InternalSalesShipmentEntity::getId)
                        .last("LIMIT " + step + " OFFSET " + begin))
                .stream()
                .map(MybatisPlusSalesShipmentRepository::summary)
                .toList();
        return new OrderPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<SalesShipmentDetailView> shipment(String tenantId, Long id) {
        return selectActive(tenantId, id).map(row -> detail(row, lines(tenantId, id)));
    }

    @Override
    public boolean existsByNo(String tenantId, String shipmentNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalSalesShipmentEntity>lambdaQuery()
                .eq(InternalSalesShipmentEntity::getTenantId, tenantId)
                .eq(InternalSalesShipmentEntity::getShipmentNo, shipmentNo)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesShipmentDetailView create(
            String tenantId, String shipmentNo, SalesShipmentWrite command, String actorId) {
        LocalDateTime now = now();
        InternalSalesShipmentEntity entity = entity(tenantId, shipmentNo, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
            insertLines(tenantId, entity.getId(), command.lines(), actorId, now);
            refreshOrderShipmentTime(tenantId, command.salesOrderId(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("销售发货单号已存在或销售发货单引用数据无效");
        }
        return shipment(tenantId, entity.getId()).orElseThrow(() -> notFound("销售发货单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesShipmentDetailView update(
            String tenantId, Long id, SalesShipmentWrite command, String actorId) {
        InternalSalesShipmentEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售发货单不存在"));
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesShipmentEntity>lambdaUpdate()
                .set(InternalSalesShipmentEntity::getConnectorId, uuidText(command.connectorId()))
                .set(InternalSalesShipmentEntity::getSourceSystemCode, command.sourceSystemCode())
                .set(InternalSalesShipmentEntity::getSourceDocumentNo, command.sourceDocumentNo())
                .set(InternalSalesShipmentEntity::getSalesOrderId, command.salesOrderId())
                .set(InternalSalesShipmentEntity::getSalesOrderNoSnapshot, command.salesOrderNoSnapshot())
                .set(InternalSalesShipmentEntity::getCustomerId, command.customerId())
                .set(InternalSalesShipmentEntity::getCustomerCodeSnapshot, command.customerCodeSnapshot())
                .set(InternalSalesShipmentEntity::getCustomerNameSnapshot, command.customerNameSnapshot())
                .set(InternalSalesShipmentEntity::getContactPhoneSnapshot, command.contactPhoneSnapshot())
                .set(InternalSalesShipmentEntity::getRegionCode, command.regionCode())
                .set(InternalSalesShipmentEntity::getOwnerStaffCode, command.ownerStaffCode())
                .set(InternalSalesShipmentEntity::getWarehouseId, command.warehouseId())
                .set(InternalSalesShipmentEntity::getStockOutOrderId, command.stockOutOrderId())
                .set(InternalSalesShipmentEntity::getStockOutNo, command.stockOutNo())
                .set(InternalSalesShipmentEntity::getShipmentStatusCode, command.shipmentStatusCode())
                .set(InternalSalesShipmentEntity::getLogisticsCompany, command.logisticsCompany())
                .set(InternalSalesShipmentEntity::getTrackingNo, command.trackingNo())
                .set(InternalSalesShipmentEntity::getShipTime, local(command.shipTime()))
                .set(InternalSalesShipmentEntity::getTotalQuantity, command.totalQuantity())
                .set(InternalSalesShipmentEntity::getRemark, command.remark())
                .set(InternalSalesShipmentEntity::getRevision, command.revision() + 1)
                .set(InternalSalesShipmentEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesShipmentEntity::getUpdatedTime, now)
                .eq(InternalSalesShipmentEntity::getTenantId, tenantId)
                .eq(InternalSalesShipmentEntity::getId, id)
                .eq(InternalSalesShipmentEntity::getRevision, command.revision())
                .eq(InternalSalesShipmentEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售发货单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, actorId, now);
        insertLines(tenantId, id, command.lines(), actorId, now);
        refreshOrderShipmentTime(tenantId, existing.getSalesOrderId(), actorId, now);
        if (!java.util.Objects.equals(existing.getSalesOrderId(), command.salesOrderId())) {
            refreshOrderShipmentTime(tenantId, command.salesOrderId(), actorId, now);
        }
        return shipment(tenantId, id).orElseThrow(() -> notFound("销售发货单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        InternalSalesShipmentEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("销售发货单不存在"));
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalSalesShipmentEntity>lambdaUpdate()
                .set(InternalSalesShipmentEntity::getDeleted, 1)
                .set(InternalSalesShipmentEntity::getRevision, revision + 1)
                .set(InternalSalesShipmentEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesShipmentEntity::getUpdatedTime, now)
                .eq(InternalSalesShipmentEntity::getTenantId, tenantId)
                .eq(InternalSalesShipmentEntity::getId, id)
                .eq(InternalSalesShipmentEntity::getRevision, revision)
                .eq(InternalSalesShipmentEntity::getDeleted, 0));
        if (updated != 1) throw conflict("销售发货单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, actorId, now);
        refreshOrderShipmentTime(tenantId, existing.getSalesOrderId(), actorId, now);
    }

    private Optional<InternalSalesShipmentEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalSalesShipmentEntity>lambdaQuery()
                .eq(InternalSalesShipmentEntity::getTenantId, tenantId)
                .eq(InternalSalesShipmentEntity::getId, id)
                .eq(InternalSalesShipmentEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private void refreshOrderShipmentTime(String tenantId, Long salesOrderId, String actorId, LocalDateTime now) {
        if (salesOrderId == null) return;
        LocalDateTime shipmentTime = getBaseMapper().selectList(Wrappers.<InternalSalesShipmentEntity>lambdaQuery()
                        .eq(InternalSalesShipmentEntity::getTenantId, tenantId)
                        .eq(InternalSalesShipmentEntity::getSalesOrderId, salesOrderId)
                        .ne(InternalSalesShipmentEntity::getShipmentStatusCode, SalesShipmentStatus.CANCELLED.code())
                        .eq(InternalSalesShipmentEntity::getDeleted, 0))
                .stream()
                .map(InternalSalesShipmentEntity::getShipTime)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        orderMapper.update(null, Wrappers.<InternalSalesOrderEntity>lambdaUpdate()
                .set(InternalSalesOrderEntity::getShipmentTime, shipmentTime)
                .set(InternalSalesOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesOrderEntity::getUpdatedTime, now)
                .setSql("revision = revision + 1")
                .eq(InternalSalesOrderEntity::getTenantId, tenantId)
                .eq(InternalSalesOrderEntity::getId, salesOrderId)
                .eq(InternalSalesOrderEntity::getDeleted, 0));
    }

    private LambdaQueryWrapper<InternalSalesShipmentEntity> query(
            String tenantId, SalesShipmentSearchCriteria criteria) {
        LambdaQueryWrapper<InternalSalesShipmentEntity> query = Wrappers.<InternalSalesShipmentEntity>lambdaQuery()
                .eq(InternalSalesShipmentEntity::getTenantId, tenantId)
                .eq(InternalSalesShipmentEntity::getDeleted, 0);
        if (criteria.shipmentNo() != null) query.like(InternalSalesShipmentEntity::getShipmentNo, criteria.shipmentNo());
        if (criteria.salesOrderNo() != null) query.like(InternalSalesShipmentEntity::getSalesOrderNoSnapshot, criteria.salesOrderNo());
        if (criteria.customerName() != null) query.like(InternalSalesShipmentEntity::getCustomerNameSnapshot, criteria.customerName());
        if (criteria.trackingNo() != null) query.like(InternalSalesShipmentEntity::getTrackingNo, criteria.trackingNo());
        if (criteria.shipmentStatusCode() != null) query.eq(InternalSalesShipmentEntity::getShipmentStatusCode, criteria.shipmentStatusCode());
        if (criteria.shipTimeFrom() != null) query.ge(InternalSalesShipmentEntity::getShipTime, local(criteria.shipTimeFrom()));
        if (criteria.shipTimeTo() != null) query.le(InternalSalesShipmentEntity::getShipTime, local(criteria.shipTimeTo()));
        return query;
    }

    private InternalSalesShipmentEntity entity(
            String tenantId, String shipmentNo, SalesShipmentWrite command, String actorId, LocalDateTime now) {
        InternalSalesShipmentEntity entity = new InternalSalesShipmentEntity();
        entity.setTenantId(tenantId);
        entity.setShipmentNo(shipmentNo);
        entity.setConnectorId(uuidText(command.connectorId()));
        entity.setSourceSystemCode(command.sourceSystemCode());
        entity.setSourceDocumentNo(command.sourceDocumentNo());
        entity.setSalesOrderId(command.salesOrderId());
        entity.setSalesOrderNoSnapshot(command.salesOrderNoSnapshot());
        entity.setCustomerId(command.customerId());
        entity.setCustomerCodeSnapshot(command.customerCodeSnapshot());
        entity.setCustomerNameSnapshot(command.customerNameSnapshot());
        entity.setContactPhoneSnapshot(command.contactPhoneSnapshot());
        entity.setRegionCode(command.regionCode());
        entity.setOwnerStaffCode(command.ownerStaffCode());
        entity.setWarehouseId(command.warehouseId());
        entity.setStockOutOrderId(command.stockOutOrderId());
        entity.setStockOutNo(command.stockOutNo());
        entity.setShipmentStatusCode(command.shipmentStatusCode());
        entity.setLogisticsCompany(command.logisticsCompany());
        entity.setTrackingNo(command.trackingNo());
        entity.setShipTime(local(command.shipTime()));
        entity.setTotalQuantity(command.totalQuantity());
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private void insertLines(String tenantId, Long shipmentId, List<SalesShipmentLineWrite> lines,
                             String actorId, LocalDateTime now) {
        for (SalesShipmentLineWrite item : lines) {
            InternalSalesShipmentLineEntity entity = new InternalSalesShipmentLineEntity();
            entity.setTenantId(tenantId);
            entity.setShipmentId(shipmentId);
            entity.setSalesOrderLineId(item.salesOrderLineId());
            entity.setLineNo(item.lineNo());
            entity.setProductId(item.productId());
            entity.setProductVariantId(item.productVariantId());
            entity.setProductCodeSnapshot(item.productCodeSnapshot());
            entity.setSkuCodeSnapshot(item.skuCodeSnapshot());
            entity.setProductNameSnapshot(item.productNameSnapshot());
            entity.setSpecificationSnapshot(item.specificationSnapshot());
            entity.setUnitCode(item.unitCode());
            entity.setShippedQuantity(item.shippedQuantity());
            entity.setRemark(item.remark());
            entity.setRevision(1);
            entity.setCreatedBy(auditActor(actorId));
            entity.setCreatedTime(now);
            entity.setUpdatedBy(auditActor(actorId));
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            lineMapper.insert(entity);
        }
    }

    private void logicDeleteLines(String tenantId, Long shipmentId, String actorId, LocalDateTime now) {
        lineMapper.update(null, Wrappers.<InternalSalesShipmentLineEntity>lambdaUpdate()
                .set(InternalSalesShipmentLineEntity::getDeleted, 1)
                .setSql("revision = revision + 1")
                .set(InternalSalesShipmentLineEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalSalesShipmentLineEntity::getUpdatedTime, now)
                .eq(InternalSalesShipmentLineEntity::getTenantId, tenantId)
                .eq(InternalSalesShipmentLineEntity::getShipmentId, shipmentId)
                .eq(InternalSalesShipmentLineEntity::getDeleted, 0));
    }

    private List<SalesShipmentLineView> lines(String tenantId, Long shipmentId) {
        return lineMapper.selectList(Wrappers.<InternalSalesShipmentLineEntity>lambdaQuery()
                        .eq(InternalSalesShipmentLineEntity::getTenantId, tenantId)
                        .eq(InternalSalesShipmentLineEntity::getShipmentId, shipmentId)
                        .eq(InternalSalesShipmentLineEntity::getDeleted, 0)
                        .orderByAsc(InternalSalesShipmentLineEntity::getLineNo))
                .stream()
                .map(MybatisPlusSalesShipmentRepository::line)
                .toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static SalesShipmentSummaryView summary(InternalSalesShipmentEntity entity) {
        return new SalesShipmentSummaryView(entity.getId(), entity.getShipmentNo(),
                uuid(entity.getConnectorId()), entity.getSourceSystemCode(), entity.getSourceDocumentNo(),
                entity.getSalesOrderId(), entity.getSalesOrderNoSnapshot(), entity.getCustomerId(),
                entity.getCustomerCodeSnapshot(), entity.getCustomerNameSnapshot(),
                entity.getContactPhoneSnapshot(), entity.getRegionCode(), entity.getOwnerStaffCode(),
                entity.getWarehouseId(), entity.getStockOutOrderId(), entity.getStockOutNo(),
                entity.getShipmentStatusCode(), entity.getLogisticsCompany(), entity.getTrackingNo(),
                instant(entity.getShipTime()), entity.getTotalQuantity(), entity.getRevision(),
                instant(entity.getUpdatedTime()));
    }

    private static SalesShipmentDetailView detail(
            InternalSalesShipmentEntity entity, List<SalesShipmentLineView> lines) {
        return new SalesShipmentDetailView(entity.getId(), entity.getShipmentNo(),
                uuid(entity.getConnectorId()), entity.getSourceSystemCode(), entity.getSourceDocumentNo(),
                entity.getSalesOrderId(), entity.getSalesOrderNoSnapshot(), entity.getCustomerId(),
                entity.getCustomerCodeSnapshot(), entity.getCustomerNameSnapshot(),
                entity.getContactPhoneSnapshot(), entity.getRegionCode(), entity.getOwnerStaffCode(),
                entity.getWarehouseId(), entity.getStockOutOrderId(), entity.getStockOutNo(),
                entity.getShipmentStatusCode(), entity.getLogisticsCompany(), entity.getTrackingNo(),
                instant(entity.getShipTime()), entity.getTotalQuantity(), entity.getRemark(),
                entity.getRevision(), entity.getCreatedBy(), instant(entity.getCreatedTime()),
                entity.getUpdatedBy(), instant(entity.getUpdatedTime()), lines);
    }

    private static SalesShipmentLineView line(InternalSalesShipmentLineEntity entity) {
        return new SalesShipmentLineView(entity.getId(), entity.getSalesOrderLineId(),
                entity.getLineNo(), entity.getProductId(), entity.getProductVariantId(),
                entity.getProductCodeSnapshot(), entity.getSkuCodeSnapshot(),
                entity.getProductNameSnapshot(), entity.getSpecificationSnapshot(),
                entity.getUnitCode(), entity.getShippedQuantity(), entity.getRemark());
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String uuidText(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String auditActor(String actorId) {
        return actorId == null || actorId.isBlank() ? SYSTEM_ACTOR : actorId;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
