package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderLineView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderLineWrite;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderSearchCriteria;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderWrite;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProductVariantSnapshot;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalSupplierProfileEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalSupplierProfileMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 采购订单仓储；订单头与明细均通过 BaseMapper 和 LambdaWrapper 维护。 */
@Repository
public class MybatisPlusProcurementOrderRepository
        extends ServiceImpl<InternalProcurementOrderMapper, InternalProcurementOrderEntity>
        implements ErpProcurementOrderStore {
    private static final String ACTIVE = "ACTIVE";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String DRAFT = "DRAFT";
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InternalProcurementOrderLineMapper lineMapper;
    private final InternalSupplierProfileMapper supplierMapper;
    private final InternalInventoryWarehouseMapper warehouseMapper;
    private final InternalProductMapper productMapper;
    private final InternalProductVariantMapper variantMapper;
    private final Clock clock;

    public MybatisPlusProcurementOrderRepository(
            InternalProcurementOrderMapper mapper,
            InternalProcurementOrderLineMapper lineMapper,
            InternalSupplierProfileMapper supplierMapper,
            InternalInventoryWarehouseMapper warehouseMapper,
            InternalProductMapper productMapper,
            InternalProductVariantMapper variantMapper,
            Clock erpClock) {
        this.baseMapper = mapper;
        this.lineMapper = lineMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.productMapper = productMapper;
        this.variantMapper = variantMapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalProcurementOrderSummaryView> procurementOrders(
            String tenantId, int begin, int step, ProcurementOrderSearchCriteria criteria) {
        InternalProcurementOrderMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalProcurementOrderEntity> page = mapper.selectList(query(tenantId, criteria)
                .orderByDesc(InternalProcurementOrderEntity::getCreatedTime)
                .orderByDesc(InternalProcurementOrderEntity::getId)
                .last("LIMIT " + step + " OFFSET " + begin));
        Set<Long> orderIds = ids(page);
        Map<Long, Long> lineCounts = lineCounts(tenantId, orderIds);
        Map<Long, String> suppliers = supplierNames(tenantId,
                page.stream().map(InternalProcurementOrderEntity::getSupplierId).collect(Collectors.toSet()));
        Map<Long, String> warehouses = warehouseNames(tenantId,
                page.stream().map(InternalProcurementOrderEntity::getTargetWarehouseId).collect(Collectors.toSet()));
        List<InternalProcurementOrderSummaryView> items = page.stream()
                .map(order -> summary(order, suppliers, warehouses, lineCounts))
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalProcurementOrderDetailView> procurementOrder(String tenantId, Long id) {
        return selectActive(tenantId, id).map(order -> detail(tenantId, order, lines(tenantId, id)));
    }

    @Override
    public boolean existsByNo(String tenantId, String procurementNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalProcurementOrderEntity>lambdaQuery()
                .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderEntity::getProcurementNo, procurementNo)) > 0;
    }

    @Override
    public boolean supplierActive(String tenantId, Long supplierId) {
        return supplierMapper.selectCount(Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                .eq(InternalSupplierProfileEntity::getId, supplierId)
                .eq(InternalSupplierProfileEntity::getStatusCode, ACTIVE)
                .eq(InternalSupplierProfileEntity::getDeleted, 0)) > 0;
    }

    @Override
    public boolean warehouseActive(String tenantId, Long warehouseId) {
        return warehouseMapper.selectCount(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                .eq(InternalInventoryWarehouseEntity::getId, warehouseId)
                .eq(InternalInventoryWarehouseEntity::getStatusCode, ACTIVE)
                .eq(InternalInventoryWarehouseEntity::getDeleted, 0)) > 0;
    }

    @Override
    public Optional<ProductVariantSnapshot> productVariant(String tenantId, Long productId, Long productVariantId) {
        InternalProductEntity product = productMapper.selectOne(Wrappers.<InternalProductEntity>lambdaQuery()
                .eq(InternalProductEntity::getTenantId, tenantId)
                .eq(InternalProductEntity::getId, productId)
                .eq(InternalProductEntity::getSubmitStatusCode, SUBMITTED)
                .eq(InternalProductEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (product == null) return Optional.empty();
        InternalProductVariantEntity variant = variantMapper.selectOne(
                Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .eq(InternalProductVariantEntity::getProductId, productId)
                        .eq(InternalProductVariantEntity::getId, productVariantId)
                        .eq(InternalProductVariantEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (variant == null) return Optional.empty();
        String unitCode = variant.getUnitCode() == null ? product.getUnitCode() : variant.getUnitCode();
        if (unitCode == null || product.getProductName() == null) return Optional.empty();
        return Optional.of(new ProductVariantSnapshot(product.getId(), variant.getId(), product.getProductCode(),
                variant.getVariantCode(), product.getProductName(), unitCode, variant.getPurchasePrice()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProcurementOrderDetailView create(
            String tenantId, String procurementNo, ProcurementOrderWrite command, String actorId) {
        LocalDateTime now = now();
        InternalProcurementOrderEntity entity = orderEntity(tenantId, procurementNo, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
            insertLines(tenantId, entity.getId(), command.lines(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("采购订单号已存在或采购订单引用数据无效");
        }
        return procurementOrder(tenantId, entity.getId()).orElseThrow(() -> notFound("采购订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProcurementOrderDetailView update(
            String tenantId, Long id, ProcurementOrderWrite command, String actorId) {
        InternalProcurementOrderEntity existing = requireEditable(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProcurementOrderEntity>lambdaUpdate()
                .set(InternalProcurementOrderEntity::getSupplierId, command.supplierId())
                .set(InternalProcurementOrderEntity::getTargetWarehouseId, command.targetWarehouseId())
                .set(InternalProcurementOrderEntity::getStatusCode, command.statusCode())
                .set(InternalProcurementOrderEntity::getExpectedArrivalTime, local(command.expectedArrivalTime()))
                .set(InternalProcurementOrderEntity::getTotalQuantity, command.totalQuantity())
                .set(InternalProcurementOrderEntity::getTotalAmount, command.totalAmount())
                .set(InternalProcurementOrderEntity::getRemark, command.remark())
                .set(InternalProcurementOrderEntity::getRevision, command.revision() + 1)
                .set(InternalProcurementOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalProcurementOrderEntity::getUpdatedTime, now)
                .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderEntity::getId, id)
                .eq(InternalProcurementOrderEntity::getRevision, command.revision())
                .eq(InternalProcurementOrderEntity::getStatusCode, existing.getStatusCode())
                .eq(InternalProcurementOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("采购订单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, actorId, now);
        insertLines(tenantId, id, command.lines(), actorId, now);
        return procurementOrder(tenantId, id).orElseThrow(() -> notFound("采购订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        InternalProcurementOrderEntity existing = requireEditable(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalProcurementOrderEntity>lambdaUpdate()
                .set(InternalProcurementOrderEntity::getDeleted, 1)
                .set(InternalProcurementOrderEntity::getRevision, revision + 1)
                .set(InternalProcurementOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalProcurementOrderEntity::getUpdatedTime, now)
                .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderEntity::getId, id)
                .eq(InternalProcurementOrderEntity::getRevision, revision)
                .eq(InternalProcurementOrderEntity::getStatusCode, existing.getStatusCode())
                .eq(InternalProcurementOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("采购订单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, actorId, now);
    }

    private InternalProcurementOrderEntity requireEditable(String tenantId, Long id) {
        InternalProcurementOrderEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("采购订单不存在"));
        if (externalSource(existing.getSourceSystemCode())) {
            throw conflict("外部来源采购订单不能编辑或删除");
        }
        if (!DRAFT.equals(existing.getStatusCode()) && !SUBMITTED.equals(existing.getStatusCode())) {
            throw conflict("采购订单已入库，不能直接修改或删除");
        }
        return existing;
    }

    private Optional<InternalProcurementOrderEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalProcurementOrderEntity>lambdaQuery()
                .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderEntity::getId, id)
                .eq(InternalProcurementOrderEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private LambdaQueryWrapper<InternalProcurementOrderEntity> query(
            String tenantId, ProcurementOrderSearchCriteria criteria) {
        LambdaQueryWrapper<InternalProcurementOrderEntity> query =
                Wrappers.<InternalProcurementOrderEntity>lambdaQuery()
                        .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                        .eq(InternalProcurementOrderEntity::getDeleted, 0);
        if (criteria.procurementNo() != null) {
            query.and(value -> value
                    .like(InternalProcurementOrderEntity::getProcurementNo, criteria.procurementNo())
                    .or()
                    .like(InternalProcurementOrderEntity::getSourceDocumentNo, criteria.procurementNo())
                    .or()
                    .like(InternalProcurementOrderEntity::getRemark, criteria.procurementNo()));
        }
        if (criteria.supplierId() != null) {
            query.eq(InternalProcurementOrderEntity::getSupplierId, criteria.supplierId());
        }
        if (criteria.targetWarehouseId() != null) {
            query.eq(InternalProcurementOrderEntity::getTargetWarehouseId, criteria.targetWarehouseId());
        }
        if (criteria.statusCode() != null) {
            query.eq(InternalProcurementOrderEntity::getStatusCode, criteria.statusCode());
        }
        if (criteria.expectedArrivalFrom() != null) {
            query.ge(InternalProcurementOrderEntity::getExpectedArrivalTime, local(criteria.expectedArrivalFrom()));
        }
        if (criteria.expectedArrivalTo() != null) {
            query.le(InternalProcurementOrderEntity::getExpectedArrivalTime, local(criteria.expectedArrivalTo()));
        }
        return query;
    }

    private InternalProcurementOrderEntity orderEntity(String tenantId, String procurementNo,
                                                       ProcurementOrderWrite command, String actorId,
                                                       LocalDateTime now) {
        InternalProcurementOrderEntity entity = new InternalProcurementOrderEntity();
        entity.setTenantId(tenantId);
        entity.setProcurementNo(procurementNo);
        entity.setSupplierId(command.supplierId());
        entity.setTargetWarehouseId(command.targetWarehouseId());
        entity.setStatusCode(command.statusCode());
        entity.setExpectedArrivalTime(local(command.expectedArrivalTime()));
        entity.setTotalQuantity(command.totalQuantity());
        entity.setTotalAmount(command.totalAmount());
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private void insertLines(String tenantId, Long orderId, List<ProcurementOrderLineWrite> lines,
                             String actorId, LocalDateTime now) {
        for (ProcurementOrderLineWrite line : lines) {
            InternalProcurementOrderLineEntity entity = new InternalProcurementOrderLineEntity();
            entity.setTenantId(tenantId);
            entity.setProcurementOrderId(orderId);
            entity.setLineNo(line.lineNo());
            entity.setProductId(line.productId());
            entity.setProductVariantId(line.productVariantId());
            entity.setProductCodeSnapshot(line.productCode());
            entity.setVariantCodeSnapshot(line.variantCode());
            entity.setProductNameSnapshot(line.productName());
            entity.setUnitCode(line.unitCode());
            entity.setQuantity(line.quantity());
            entity.setUnitPrice(line.unitPrice());
            entity.setLineAmount(line.lineAmount());
            entity.setReceivedQuantity(ZERO);
            entity.setRemark(line.remark());
            entity.setRevision(1);
            entity.setCreatedBy(auditActor(actorId));
            entity.setCreatedTime(now);
            entity.setUpdatedBy(auditActor(actorId));
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            lineMapper.insert(entity);
        }
    }

    private void logicDeleteLines(String tenantId, Long orderId, String actorId, LocalDateTime now) {
        lineMapper.update(null, Wrappers.<InternalProcurementOrderLineEntity>lambdaUpdate()
                .set(InternalProcurementOrderLineEntity::getDeleted, 1)
                .setSql("revision = revision + 1")
                .set(InternalProcurementOrderLineEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalProcurementOrderLineEntity::getUpdatedTime, now)
                .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderLineEntity::getProcurementOrderId, orderId)
                .eq(InternalProcurementOrderLineEntity::getDeleted, 0));
    }

    private InternalProcurementOrderDetailView detail(String tenantId, InternalProcurementOrderEntity order,
                                                     List<InternalProcurementOrderLineEntity> lines) {
        return new InternalProcurementOrderDetailView(order.getId(), order.getProcurementNo(),
                order.getSourceSystemCode(), order.getSourceDocumentNo(),
                order.getSupplierId(), supplierNames(tenantId, Set.of(order.getSupplierId())).get(order.getSupplierId()),
                order.getTargetWarehouseId(),
                warehouseNames(tenantId, Set.of(order.getTargetWarehouseId())).get(order.getTargetWarehouseId()),
                order.getStatusCode(), instant(order.getExpectedArrivalTime()), order.getTotalQuantity(),
                order.getTotalAmount(), lines.stream()
                        .map(MybatisPlusProcurementOrderRepository::lineView)
                        .toList(),
                order.getRemark(), order.getRevision(), order.getCreatedBy(), instant(order.getCreatedTime()),
                order.getUpdatedBy(), instant(order.getUpdatedTime()));
    }

    private InternalProcurementOrderSummaryView summary(
            InternalProcurementOrderEntity order, Map<Long, String> suppliers, Map<Long, String> warehouses,
            Map<Long, Long> lineCounts) {
        return new InternalProcurementOrderSummaryView(order.getId(), order.getProcurementNo(),
                order.getSourceSystemCode(), order.getSourceDocumentNo(),
                order.getSupplierId(), suppliers.get(order.getSupplierId()), order.getTargetWarehouseId(),
                warehouses.get(order.getTargetWarehouseId()), order.getStatusCode(),
                instant(order.getExpectedArrivalTime()), order.getTotalQuantity(), order.getTotalAmount(),
                Math.toIntExact(lineCounts.getOrDefault(order.getId(), 0L)), order.getRevision(),
                instant(order.getUpdatedTime()));
    }

    private List<InternalProcurementOrderLineEntity> lines(String tenantId, Long orderId) {
        return lineMapper.selectList(Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderLineEntity::getProcurementOrderId, orderId)
                .eq(InternalProcurementOrderLineEntity::getDeleted, 0)
                .orderByAsc(InternalProcurementOrderLineEntity::getLineNo)
                .orderByAsc(InternalProcurementOrderLineEntity::getId));
    }

    private Map<Long, Long> lineCounts(String tenantId, Set<Long> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        return lineMapper.selectList(Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                        .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                        .in(InternalProcurementOrderLineEntity::getProcurementOrderId, orderIds)
                        .eq(InternalProcurementOrderLineEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.groupingBy(
                        InternalProcurementOrderLineEntity::getProcurementOrderId,
                        Collectors.counting()));
    }

    private Map<Long, String> supplierNames(String tenantId, Set<Long> ids) {
        Set<Long> normalized = ids.stream()
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalized.isEmpty()) return Map.of();
        return supplierMapper.selectList(Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                        .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                        .in(InternalSupplierProfileEntity::getId, normalized)
                        .eq(InternalSupplierProfileEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(InternalSupplierProfileEntity::getId,
                        InternalSupplierProfileEntity::getSupplierName, (a, b) -> a));
    }

    private Map<Long, String> warehouseNames(String tenantId, Set<Long> ids) {
        Set<Long> normalized = ids.stream()
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalized.isEmpty()) return Map.of();
        return warehouseMapper.selectList(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                        .in(InternalInventoryWarehouseEntity::getId, normalized)
                        .eq(InternalInventoryWarehouseEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(InternalInventoryWarehouseEntity::getId,
                        InternalInventoryWarehouseEntity::getWarehouseName, (a, b) -> a));
    }

    private static InternalProcurementOrderLineView lineView(InternalProcurementOrderLineEntity entity) {
        return new InternalProcurementOrderLineView(entity.getId(), entity.getLineNo(), entity.getProductId(),
                entity.getProductVariantId(), entity.getProductCodeSnapshot(), entity.getVariantCodeSnapshot(),
                entity.getProductNameSnapshot(), entity.getUnitCode(), entity.getQuantity(), entity.getUnitPrice(),
                entity.getLineAmount(), entity.getReceivedQuantity(), entity.getRemark());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Set<Long> ids(List<InternalProcurementOrderEntity> orders) {
        return orders.stream()
                .map(InternalProcurementOrderEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String auditActor(String actorId) {
        return actorId == null || actorId.isBlank() ? SYSTEM_ACTOR : actorId;
    }

    private static boolean externalSource(String sourceSystemCode) {
        return sourceSystemCode != null && !sourceSystemCode.isBlank();
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
