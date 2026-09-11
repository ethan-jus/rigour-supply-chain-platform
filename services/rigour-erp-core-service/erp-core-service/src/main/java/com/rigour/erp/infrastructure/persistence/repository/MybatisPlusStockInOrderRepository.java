package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalStockInOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockInOrderLineView;
import com.rigour.erp.api.v1.model.InternalStockInOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpStockInOrderStore;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementOrderLineSnapshot;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementOrderSnapshot;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementStockInLineWrite;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementStockInWrite;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.StockInOrderSearchCriteria;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockBalanceEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockFlowEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalSupplierProfileEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockBalanceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockFlowMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalSupplierProfileMapper;
import com.rigour.erp.domain.enums.ErpStockFlowType;
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

/** MyBatis-Plus 入库单仓储；所有 CRUD 使用 BaseMapper + LambdaWrapper。 */
@Repository
public class MybatisPlusStockInOrderRepository
        extends ServiceImpl<InternalStockInOrderMapper, InternalStockInOrderEntity>
        implements ErpStockInOrderStore {
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InternalStockInOrderLineMapper stockInLineMapper;
    private final InternalProcurementOrderMapper procurementOrderMapper;
    private final InternalProcurementOrderLineMapper procurementLineMapper;
    private final InternalStockBalanceMapper stockBalanceMapper;
    private final InternalStockFlowMapper stockFlowMapper;
    private final InternalSupplierProfileMapper supplierMapper;
    private final InternalInventoryWarehouseMapper warehouseMapper;
    private final Clock clock;

    public MybatisPlusStockInOrderRepository(
            InternalStockInOrderMapper mapper,
            InternalStockInOrderLineMapper stockInLineMapper,
            InternalProcurementOrderMapper procurementOrderMapper,
            InternalProcurementOrderLineMapper procurementLineMapper,
            InternalStockBalanceMapper stockBalanceMapper,
            InternalStockFlowMapper stockFlowMapper,
            InternalSupplierProfileMapper supplierMapper,
            InternalInventoryWarehouseMapper warehouseMapper,
            Clock erpClock) {
        this.baseMapper = mapper;
        this.stockInLineMapper = stockInLineMapper;
        this.procurementOrderMapper = procurementOrderMapper;
        this.procurementLineMapper = procurementLineMapper;
        this.stockBalanceMapper = stockBalanceMapper;
        this.stockFlowMapper = stockFlowMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalStockInOrderSummaryView> stockInOrders(
            String tenantId, int begin, int step, StockInOrderSearchCriteria criteria) {
        InternalStockInOrderMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalStockInOrderEntity> page = mapper.selectList(query(tenantId, criteria)
                .orderByDesc(InternalStockInOrderEntity::getStockInTime)
                .orderByDesc(InternalStockInOrderEntity::getId)
                .last("LIMIT " + step + " OFFSET " + begin));
        Set<Long> orderIds = ids(page);
        Map<Long, LineMetrics> metrics = lineMetrics(tenantId, orderIds);
        Map<Long, String> suppliers = supplierNames(tenantId,
                page.stream().map(InternalStockInOrderEntity::getSupplierId).collect(Collectors.toSet()));
        Map<Long, String> warehouses = warehouseNames(tenantId,
                page.stream().map(InternalStockInOrderEntity::getWarehouseId).collect(Collectors.toSet()));
        List<InternalStockInOrderSummaryView> items = page.stream()
                .map(order -> summary(order, suppliers, warehouses, metrics))
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalStockInOrderDetailView> stockInOrder(String tenantId, Long id) {
        return selectActive(tenantId, id).map(order -> detail(tenantId, order, lines(tenantId, id)));
    }

    @Override
    public Optional<ProcurementOrderSnapshot> procurementOrderForStockIn(String tenantId, Long procurementOrderId) {
        InternalProcurementOrderEntity order = procurementOrderMapper.selectOne(
                Wrappers.<InternalProcurementOrderEntity>lambdaQuery()
                        .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                        .eq(InternalProcurementOrderEntity::getId, procurementOrderId)
                        .eq(InternalProcurementOrderEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (order == null) return Optional.empty();
        List<ProcurementOrderLineSnapshot> lineSnapshots = procurementLines(tenantId, procurementOrderId).stream()
                .map(MybatisPlusStockInOrderRepository::procurementLineSnapshot)
                .toList();
        return Optional.of(new ProcurementOrderSnapshot(order.getId(), order.getProcurementNo(),
                order.getSourceSystemCode(), order.getSourceDocumentNo(),
                order.getSupplierId(), order.getTargetWarehouseId(), order.getStatusCode(),
                order.getRevision(), lineSnapshots));
    }

    @Override
    public boolean existsByStockInNo(String tenantId, String stockInNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalStockInOrderEntity>lambdaQuery()
                .eq(InternalStockInOrderEntity::getTenantId, tenantId)
                .eq(InternalStockInOrderEntity::getStockInNo, stockInNo)) > 0;
    }

    @Override
    public boolean existsByFlowNo(String tenantId, String flowNo) {
        return stockFlowMapper.selectCount(Wrappers.<InternalStockFlowEntity>lambdaQuery()
                .eq(InternalStockFlowEntity::getTenantId, tenantId)
                .eq(InternalStockFlowEntity::getFlowNo, flowNo)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalStockInOrderDetailView confirmProcurementStockIn(
            String tenantId, String stockInNo, ProcurementStockInWrite command, String actorId) {
        LocalDateTime now = now();
        InternalStockInOrderEntity order = stockInOrderEntity(tenantId, stockInNo, command, actorId, now);
        try {
            getBaseMapper().insert(order);
            for (ProcurementStockInLineWrite line : command.lines()) {
                insertStockInLine(tenantId, order.getId(), line, actorId, now);
                updateProcurementLineReceived(tenantId, command.procurementOrderId(), line, actorId, now);
                StockQuantityChange quantityChange = increaseStockBalance(
                        tenantId, command.warehouseId(), line.productId(), line.productVariantId(),
                        line.quantity(), actorId, now);
                insertStockFlow(tenantId, order.getId(), stockInNo, command.warehouseId(), line,
                        quantityChange, actorId, now);
            }
            updateProcurementOrderStatus(tenantId, command, actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("入库单号已存在或入库引用数据无效");
        }
        return stockInOrder(tenantId, order.getId()).orElseThrow(() -> notFound("入库单不存在"));
    }

    private void updateProcurementLineReceived(
            String tenantId, Long procurementOrderId, ProcurementStockInLineWrite line, String actorId,
            LocalDateTime now) {
        InternalProcurementOrderLineEntity current = procurementLineMapper.selectOne(
                Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                        .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                        .eq(InternalProcurementOrderLineEntity::getId, line.procurementOrderLineId())
                        .eq(InternalProcurementOrderLineEntity::getProcurementOrderId, procurementOrderId)
                        .eq(InternalProcurementOrderLineEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (current == null) throw conflict("采购订单明细已变化，请刷新后重试");
        BigDecimal beforeReceived = zeroIfNull(current.getReceivedQuantity());
        BigDecimal afterReceived = beforeReceived.add(line.quantity());
        if (afterReceived.compareTo(current.getQuantity()) > 0) {
            throw conflict("采购入库数量不能超过未入库数量");
        }
        int updated = procurementLineMapper.update(null, Wrappers.<InternalProcurementOrderLineEntity>lambdaUpdate()
                .set(InternalProcurementOrderLineEntity::getReceivedQuantity, afterReceived)
                .set(InternalProcurementOrderLineEntity::getRevision,
                        current.getRevision() == null ? 1 : current.getRevision() + 1)
                .set(InternalProcurementOrderLineEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalProcurementOrderLineEntity::getUpdatedTime, now)
                .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderLineEntity::getId, line.procurementOrderLineId())
                .eq(InternalProcurementOrderLineEntity::getProcurementOrderId, procurementOrderId)
                .eq(InternalProcurementOrderLineEntity::getReceivedQuantity, beforeReceived)
                .eq(InternalProcurementOrderLineEntity::getDeleted, 0));
        if (updated != 1) throw conflict("采购订单明细已被其他人入库，请刷新后重试");
    }

    private StockQuantityChange increaseStockBalance(
            String tenantId, Long warehouseId, Long productId, Long productVariantId, BigDecimal quantity,
            String actorId, LocalDateTime now) {
        InternalStockBalanceEntity existing = stockBalanceMapper.selectOne(
                Wrappers.<InternalStockBalanceEntity>lambdaQuery()
                        .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                        .eq(InternalStockBalanceEntity::getWarehouseId, warehouseId)
                        .eq(InternalStockBalanceEntity::getProductId, productId)
                        .eq(InternalStockBalanceEntity::getProductVariantId, productVariantId)
                        .eq(InternalStockBalanceEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (existing == null) {
            InternalStockBalanceEntity created = new InternalStockBalanceEntity();
            created.setTenantId(tenantId);
            created.setWarehouseId(warehouseId);
            created.setProductId(productId);
            created.setProductVariantId(productVariantId);
            created.setAvailableQuantity(quantity);
            created.setLockedQuantity(ZERO);
            created.setInTransitQuantity(ZERO);
            created.setRevision(1);
            created.setCreatedBy(auditActor(actorId));
            created.setCreatedTime(now);
            created.setUpdatedBy(auditActor(actorId));
            created.setUpdatedTime(now);
            created.setDeleted(0);
            stockBalanceMapper.insert(created);
            return new StockQuantityChange(ZERO, quantity);
        }
        BigDecimal before = zeroIfNull(existing.getAvailableQuantity());
        BigDecimal after = before.add(quantity);
        int updated = stockBalanceMapper.update(null, Wrappers.<InternalStockBalanceEntity>lambdaUpdate()
                .set(InternalStockBalanceEntity::getAvailableQuantity, after)
                .set(InternalStockBalanceEntity::getRevision, existing.getRevision() + 1)
                .set(InternalStockBalanceEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalStockBalanceEntity::getUpdatedTime, now)
                .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                .eq(InternalStockBalanceEntity::getId, existing.getId())
                .eq(InternalStockBalanceEntity::getRevision, existing.getRevision())
                .eq(InternalStockBalanceEntity::getDeleted, 0));
        if (updated != 1) throw conflict("库存余额已被其他单据修改，请重试入库");
        return new StockQuantityChange(before, after);
    }

    private void updateProcurementOrderStatus(
            String tenantId, ProcurementStockInWrite command, String actorId, LocalDateTime now) {
        int updated = procurementOrderMapper.update(null, Wrappers.<InternalProcurementOrderEntity>lambdaUpdate()
                .set(InternalProcurementOrderEntity::getStatusCode, command.nextProcurementStatusCode())
                .set(InternalProcurementOrderEntity::getRevision, command.procurementRevision() + 1)
                .set(InternalProcurementOrderEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalProcurementOrderEntity::getUpdatedTime, now)
                .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderEntity::getId, command.procurementOrderId())
                .eq(InternalProcurementOrderEntity::getRevision, command.procurementRevision())
                .eq(InternalProcurementOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("采购订单已被其他人修改，请刷新后重试");
    }

    private void insertStockInLine(
            String tenantId, Long stockInOrderId, ProcurementStockInLineWrite line, String actorId,
            LocalDateTime now) {
        InternalStockInOrderLineEntity entity = new InternalStockInOrderLineEntity();
        entity.setTenantId(tenantId);
        entity.setStockInOrderId(stockInOrderId);
        entity.setLineNo(line.lineNo());
        entity.setProcurementOrderLineId(line.procurementOrderLineId());
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setProductCodeSnapshot(line.productCode());
        entity.setVariantCodeSnapshot(line.variantCode());
        entity.setProductNameSnapshot(line.productName());
        entity.setUnitCode(line.unitCode());
        entity.setQuantity(line.quantity());
        entity.setUnitPrice(line.unitPrice());
        entity.setAmount(line.amount());
        entity.setRemark(line.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        stockInLineMapper.insert(entity);
    }

    private void insertStockFlow(
            String tenantId, Long stockInOrderId, String stockInNo, Long warehouseId, ProcurementStockInLineWrite line,
            StockQuantityChange quantityChange, String actorId, LocalDateTime now) {
        InternalStockFlowEntity entity = new InternalStockFlowEntity();
        entity.setTenantId(tenantId);
        entity.setFlowNo(line.flowNo());
        entity.setWarehouseId(warehouseId);
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setBusinessTypeCode(ErpStockFlowType.PURCHASE_IN.code());
        entity.setBusinessOrderId(stockInOrderId);
        entity.setBusinessOrderNo(stockInNo);
        entity.setQuantityDelta(line.quantity());
        entity.setBeforeQuantity(quantityChange.beforeQuantity());
        entity.setAfterQuantity(quantityChange.afterQuantity());
        entity.setRemark(line.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        stockFlowMapper.insert(entity);
    }

    private InternalStockInOrderEntity stockInOrderEntity(
            String tenantId, String stockInNo, ProcurementStockInWrite command, String actorId, LocalDateTime now) {
        InternalStockInOrderEntity entity = new InternalStockInOrderEntity();
        entity.setTenantId(tenantId);
        entity.setStockInNo(stockInNo);
        entity.setStockInTypeCode(command.stockInTypeCode());
        entity.setProcurementOrderId(command.procurementOrderId());
        entity.setProcurementNo(command.procurementNo());
        entity.setWarehouseId(command.warehouseId());
        entity.setSupplierId(command.supplierId());
        entity.setStatusCode(command.statusCode());
        entity.setStockInTime(local(command.stockInTime()));
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private InternalStockInOrderDetailView detail(
            String tenantId, InternalStockInOrderEntity order, List<InternalStockInOrderLineEntity> lines) {
        Map<Long, ProcurementLineDisplay> procurementLines = procurementLineDisplays(tenantId,
                lines.stream().map(InternalStockInOrderLineEntity::getProcurementOrderLineId).collect(Collectors.toSet()));
        LineMetrics metrics = metrics(lines);
        return new InternalStockInOrderDetailView(order.getId(), order.getStockInNo(),
                order.getSourceSystemCode(), order.getSourceDocumentNo(), order.getStockInTypeCode(),
                order.getProcurementOrderId(), order.getProcurementNo(),
                order.getTransferOrderId(), order.getTransferOrderNo(), order.getWarehouseId(),
                mapValue(warehouseNames(tenantId, singletonId(order.getWarehouseId())), order.getWarehouseId()),
                order.getSupplierId(), mapValue(supplierNames(tenantId, singletonId(order.getSupplierId())),
                order.getSupplierId()),
                order.getStatusCode(), instant(order.getStockInTime()), metrics.totalQuantity(), metrics.totalAmount(),
                lines.stream().map(line -> lineView(line, procurementLines)).toList(), order.getRemark(),
                order.getRevision(), order.getCreatedBy(), instant(order.getCreatedTime()), order.getUpdatedBy(),
                instant(order.getUpdatedTime()));
    }

    private InternalStockInOrderSummaryView summary(
            InternalStockInOrderEntity order, Map<Long, String> suppliers, Map<Long, String> warehouses,
            Map<Long, LineMetrics> metricsByOrder) {
        LineMetrics metrics = metricsByOrder.getOrDefault(order.getId(), LineMetrics.ZERO);
        return new InternalStockInOrderSummaryView(order.getId(), order.getStockInNo(),
                order.getSourceSystemCode(), order.getSourceDocumentNo(), order.getStockInTypeCode(),
                order.getProcurementOrderId(), order.getProcurementNo(),
                order.getTransferOrderId(), order.getTransferOrderNo(), order.getWarehouseId(),
                mapValue(warehouses, order.getWarehouseId()), order.getSupplierId(),
                mapValue(suppliers, order.getSupplierId()),
                order.getStatusCode(), instant(order.getStockInTime()), metrics.totalQuantity(), metrics.totalAmount(),
                metrics.lineCount(), order.getRevision(), instant(order.getUpdatedTime()));
    }

    private List<InternalStockInOrderLineEntity> lines(String tenantId, Long stockInOrderId) {
        return stockInLineMapper.selectList(Wrappers.<InternalStockInOrderLineEntity>lambdaQuery()
                .eq(InternalStockInOrderLineEntity::getTenantId, tenantId)
                .eq(InternalStockInOrderLineEntity::getStockInOrderId, stockInOrderId)
                .eq(InternalStockInOrderLineEntity::getDeleted, 0)
                .orderByAsc(InternalStockInOrderLineEntity::getLineNo)
                .orderByAsc(InternalStockInOrderLineEntity::getId));
    }

    private List<InternalProcurementOrderLineEntity> procurementLines(String tenantId, Long procurementOrderId) {
        return procurementLineMapper.selectList(Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderLineEntity::getProcurementOrderId, procurementOrderId)
                .eq(InternalProcurementOrderLineEntity::getDeleted, 0)
                .orderByAsc(InternalProcurementOrderLineEntity::getLineNo)
                .orderByAsc(InternalProcurementOrderLineEntity::getId));
    }

    private Optional<InternalStockInOrderEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalStockInOrderEntity>lambdaQuery()
                .eq(InternalStockInOrderEntity::getTenantId, tenantId)
                .eq(InternalStockInOrderEntity::getId, id)
                .eq(InternalStockInOrderEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private LambdaQueryWrapper<InternalStockInOrderEntity> query(
            String tenantId, StockInOrderSearchCriteria criteria) {
        LambdaQueryWrapper<InternalStockInOrderEntity> query =
                Wrappers.<InternalStockInOrderEntity>lambdaQuery()
                        .eq(InternalStockInOrderEntity::getTenantId, tenantId)
                        .eq(InternalStockInOrderEntity::getDeleted, 0);
        if (criteria.stockInNo() != null) {
            query.and(value -> value
                    .like(InternalStockInOrderEntity::getStockInNo, criteria.stockInNo())
                    .or()
                    .like(InternalStockInOrderEntity::getSourceDocumentNo, criteria.stockInNo())
                    .or()
                    .like(InternalStockInOrderEntity::getProcurementNo, criteria.stockInNo())
                    .or()
                    .like(InternalStockInOrderEntity::getTransferOrderNo, criteria.stockInNo())
                    .or()
                    .like(InternalStockInOrderEntity::getRemark, criteria.stockInNo()));
        }
        if (criteria.stockInTypeCode() != null) {
            query.eq(InternalStockInOrderEntity::getStockInTypeCode, criteria.stockInTypeCode());
        }
        if (criteria.procurementOrderId() != null) {
            query.eq(InternalStockInOrderEntity::getProcurementOrderId, criteria.procurementOrderId());
        }
        if (criteria.warehouseId() != null) {
            query.eq(InternalStockInOrderEntity::getWarehouseId, criteria.warehouseId());
        }
        if (criteria.supplierId() != null) {
            query.eq(InternalStockInOrderEntity::getSupplierId, criteria.supplierId());
        }
        if (criteria.statusCode() != null) {
            query.eq(InternalStockInOrderEntity::getStatusCode, criteria.statusCode());
        }
        if (criteria.stockInTimeFrom() != null) {
            query.ge(InternalStockInOrderEntity::getStockInTime, local(criteria.stockInTimeFrom()));
        }
        if (criteria.stockInTimeTo() != null) {
            query.le(InternalStockInOrderEntity::getStockInTime, local(criteria.stockInTimeTo()));
        }
        return query;
    }

    private Map<Long, LineMetrics> lineMetrics(String tenantId, Set<Long> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        return stockInLineMapper.selectList(Wrappers.<InternalStockInOrderLineEntity>lambdaQuery()
                        .eq(InternalStockInOrderLineEntity::getTenantId, tenantId)
                        .in(InternalStockInOrderLineEntity::getStockInOrderId, orderIds)
                        .eq(InternalStockInOrderLineEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.groupingBy(
                        InternalStockInOrderLineEntity::getStockInOrderId,
                        Collectors.collectingAndThen(Collectors.toList(), MybatisPlusStockInOrderRepository::metrics)));
    }

    private Map<Long, ProcurementLineDisplay> procurementLineDisplays(String tenantId, Set<Long> lineIds) {
        Set<Long> normalized = lineIds.stream()
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalized.isEmpty()) return Map.of();
        return procurementLineMapper.selectList(Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                        .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                        .in(InternalProcurementOrderLineEntity::getId, normalized)
                        .eq(InternalProcurementOrderLineEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(InternalProcurementOrderLineEntity::getId,
                        line -> new ProcurementLineDisplay(line.getProductCodeSnapshot(),
                                line.getVariantCodeSnapshot(), line.getProductNameSnapshot()), (a, b) -> a));
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

    private static InternalStockInOrderLineView lineView(
            InternalStockInOrderLineEntity entity, Map<Long, ProcurementLineDisplay> procurementLines) {
        ProcurementLineDisplay display = mapValue(procurementLines, entity.getProcurementOrderLineId());
        return new InternalStockInOrderLineView(entity.getId(), entity.getLineNo(),
                entity.getProcurementOrderLineId(), entity.getTransferOrderLineId(),
                entity.getProductId(), entity.getProductVariantId(),
                firstNonBlank(entity.getProductCodeSnapshot(), display == null ? null : display.productCode()),
                firstNonBlank(entity.getVariantCodeSnapshot(), display == null ? null : display.variantCode()),
                firstNonBlank(entity.getProductNameSnapshot(), display == null ? null : display.productName()),
                entity.getUnitCode(), entity.getQuantity(),
                entity.getUnitPrice(), entity.getAmount(), entity.getRemark());
    }

    private static Set<Long> singletonId(Long id) {
        return id == null ? Set.of() : Set.of(id);
    }

    private static <T> T mapValue(Map<Long, T> values, Long key) {
        return key == null ? null : values.get(key);
    }

    private static ProcurementOrderLineSnapshot procurementLineSnapshot(InternalProcurementOrderLineEntity line) {
        return new ProcurementOrderLineSnapshot(line.getId(), line.getLineNo(), line.getProductId(),
                line.getProductVariantId(), line.getProductCodeSnapshot(), line.getVariantCodeSnapshot(),
                line.getProductNameSnapshot(), line.getUnitCode(), line.getQuantity(), line.getUnitPrice(),
                line.getLineAmount(), line.getReceivedQuantity());
    }

    private static LineMetrics metrics(List<InternalStockInOrderLineEntity> lines) {
        BigDecimal totalQuantity = lines.stream()
                .map(InternalStockInOrderLineEntity::getQuantity)
                .map(MybatisPlusStockInOrderRepository::zeroIfNull)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalAmount = lines.stream()
                .map(InternalStockInOrderLineEntity::getAmount)
                .map(MybatisPlusStockInOrderRepository::zeroIfNull)
                .reduce(ZERO, BigDecimal::add);
        return new LineMetrics(totalQuantity, totalAmount, lines.size());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Set<Long> ids(List<InternalStockInOrderEntity> orders) {
        return orders.stream()
                .map(InternalStockInOrderEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
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

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private record LineMetrics(BigDecimal totalQuantity, BigDecimal totalAmount, Integer lineCount) {
        private static final LineMetrics ZERO = new LineMetrics(BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private record ProcurementLineDisplay(String productCode, String variantCode, String productName) {
    }

    private record StockQuantityChange(BigDecimal beforeQuantity, BigDecimal afterQuantity) {
    }
}
