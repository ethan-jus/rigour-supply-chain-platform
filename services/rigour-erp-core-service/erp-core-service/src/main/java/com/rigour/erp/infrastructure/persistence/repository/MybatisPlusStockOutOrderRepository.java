package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockOutOrderLineView;
import com.rigour.erp.api.v1.model.InternalStockOutOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.ExternalGenericStockOutLineWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.ExternalGenericStockOutWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.ExternalStockOutLineWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.ExternalStockOutWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.ProductVariantSnapshot;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.SalesStockOutLineWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.SalesStockOutWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.StockOutOrderSearchCriteria;
import com.rigour.erp.domain.enums.ErpStockFlowType;
import com.rigour.erp.domain.enums.ErpStockOutType;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockBalanceEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockFlowEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockOutOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockOutOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockBalanceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockFlowMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockOutOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockOutOrderMapper;
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

/** MyBatis-Plus 出库单仓储；所有 CRUD 使用 BaseMapper + LambdaWrapper。 */
@Repository
public class MybatisPlusStockOutOrderRepository
        extends ServiceImpl<InternalStockOutOrderMapper, InternalStockOutOrderEntity>
        implements ErpStockOutOrderStore {
    private static final String ACTIVE = "ACTIVE";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InternalStockOutOrderLineMapper lineMapper;
    private final InternalStockBalanceMapper stockBalanceMapper;
    private final InternalStockFlowMapper stockFlowMapper;
    private final InternalInventoryWarehouseMapper warehouseMapper;
    private final InternalProductMapper productMapper;
    private final InternalProductVariantMapper variantMapper;
    private final Clock clock;

    public MybatisPlusStockOutOrderRepository(
            InternalStockOutOrderMapper mapper,
            InternalStockOutOrderLineMapper lineMapper,
            InternalStockBalanceMapper stockBalanceMapper,
            InternalStockFlowMapper stockFlowMapper,
            InternalInventoryWarehouseMapper warehouseMapper,
            InternalProductMapper productMapper,
            InternalProductVariantMapper variantMapper,
            Clock erpClock) {
        this.baseMapper = mapper;
        this.lineMapper = lineMapper;
        this.stockBalanceMapper = stockBalanceMapper;
        this.stockFlowMapper = stockFlowMapper;
        this.warehouseMapper = warehouseMapper;
        this.productMapper = productMapper;
        this.variantMapper = variantMapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalStockOutOrderSummaryView> stockOutOrders(
            String tenantId, int begin, int step, StockOutOrderSearchCriteria criteria) {
        InternalStockOutOrderMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalStockOutOrderEntity> page = mapper.selectList(query(tenantId, criteria)
                .orderByDesc(InternalStockOutOrderEntity::getStockOutTime)
                .orderByDesc(InternalStockOutOrderEntity::getId)
                .last("LIMIT " + step + " OFFSET " + begin));
        Set<Long> orderIds = ids(page);
        Map<Long, LineMetrics> metrics = lineMetrics(tenantId, orderIds);
        Map<Long, String> warehouses = warehouseNames(tenantId,
                page.stream().map(InternalStockOutOrderEntity::getWarehouseId).collect(Collectors.toSet()));
        List<InternalStockOutOrderSummaryView> items = page.stream()
                .map(order -> summary(order, warehouses, metrics))
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalStockOutOrderDetailView> stockOutOrder(String tenantId, Long id) {
        return selectActive(tenantId, id).map(order -> detail(tenantId, order, lines(tenantId, id)));
    }

    @Override
    public Optional<InternalStockOutOrderDetailView> stockOutOrderBySource(
            String tenantId, String connectorId, String sourceSystemCode, String sourceDocumentNo) {
        if (connectorId == null || sourceSystemCode == null || sourceDocumentNo == null) return Optional.empty();
        InternalStockOutOrderEntity row = getBaseMapper().selectOne(
                Wrappers.<InternalStockOutOrderEntity>lambdaQuery()
                        .eq(InternalStockOutOrderEntity::getTenantId, tenantId)
                        .eq(InternalStockOutOrderEntity::getConnectorId, connectorId)
                        .eq(InternalStockOutOrderEntity::getSourceSystemCode, sourceSystemCode)
                        .eq(InternalStockOutOrderEntity::getSourceDocumentNo, sourceDocumentNo)
                        .eq(InternalStockOutOrderEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        return Optional.ofNullable(row).map(order -> detail(tenantId, order, lines(tenantId, order.getId())));
    }

    @Override
    public boolean existsByStockOutNo(String tenantId, String stockOutNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalStockOutOrderEntity>lambdaQuery()
                .eq(InternalStockOutOrderEntity::getTenantId, tenantId)
                .eq(InternalStockOutOrderEntity::getStockOutNo, stockOutNo)) > 0;
    }

    @Override
    public boolean existsByFlowNo(String tenantId, String flowNo) {
        return stockFlowMapper.selectCount(Wrappers.<InternalStockFlowEntity>lambdaQuery()
                .eq(InternalStockFlowEntity::getTenantId, tenantId)
                .eq(InternalStockFlowEntity::getFlowNo, flowNo)) > 0;
    }

    @Override
    public boolean existsActiveSalesStockOut(String tenantId, Long salesOrderId) {
        return getBaseMapper().selectCount(Wrappers.<InternalStockOutOrderEntity>lambdaQuery()
                .eq(InternalStockOutOrderEntity::getTenantId, tenantId)
                .eq(InternalStockOutOrderEntity::getSalesOrderId, salesOrderId)
                .eq(InternalStockOutOrderEntity::getStockOutTypeCode, ErpStockOutType.SALES.code())
                .eq(InternalStockOutOrderEntity::getDeleted, 0)) > 0;
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
    public Optional<ProductVariantSnapshot> productVariant(
            String tenantId, Long productId, Long productVariantId) {
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
        return Optional.of(new ProductVariantSnapshot(product.getId(), variant.getId(),
                product.getProductCode(), variant.getVariantCode(), product.getProductName(), unitCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalStockOutOrderDetailView confirmSalesStockOut(
            String tenantId, String stockOutNo, SalesStockOutWrite command, String actorId) {
        LocalDateTime now = now();
        InternalStockOutOrderEntity order = stockOutOrderEntity(tenantId, stockOutNo, command, actorId, now);
        try {
            getBaseMapper().insert(order);
            for (SalesStockOutLineWrite line : command.lines()) {
                insertStockOutLine(tenantId, order.getId(), line, actorId, now);
                StockQuantityChange quantityChange = decreaseStockBalance(
                        tenantId, command.warehouseId(), line.productId(), line.productVariantId(),
                        line.quantity(), actorId, now);
                insertStockFlow(tenantId, order.getId(), stockOutNo, command.warehouseId(), line,
                        quantityChange, actorId, now);
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("出库单号已存在或出库引用数据无效");
        }
        return stockOutOrder(tenantId, order.getId()).orElseThrow(() -> notFound("出库单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalStockOutOrderDetailView confirmExternalStockOut(
            String tenantId, String stockOutNo, ExternalStockOutWrite command, String actorId) {
        Optional<InternalStockOutOrderDetailView> existing = stockOutOrderBySource(
                tenantId, command.connectorId(), command.sourceSystemCode(), command.sourceDocumentNo());
        if (existing.isPresent()) return existing.get();
        LocalDateTime now = now();
        InternalStockOutOrderEntity order = stockOutOrderEntity(tenantId, stockOutNo, command, actorId, now);
        try {
            getBaseMapper().insert(order);
            for (ExternalStockOutLineWrite line : command.lines()) {
                insertStockOutLine(tenantId, order.getId(), line, actorId, now);
                if (command.affectStockBalance()) {
                    StockQuantityChange quantityChange = decreaseStockBalance(
                            tenantId, command.warehouseId(), line.productId(), line.productVariantId(),
                            line.quantity(), actorId, now);
                    insertStockFlow(tenantId, order.getId(), stockOutNo, command.warehouseId(),
                            command.stockOutTypeCode(), line, quantityChange, actorId, now);
                }
            }
        } catch (DataIntegrityViolationException exception) {
            return stockOutOrderBySource(
                    tenantId, command.connectorId(), command.sourceSystemCode(), command.sourceDocumentNo())
                    .orElseThrow(() -> conflict("出库单号或来源出库单号已存在，或出库引用数据无效"));
        }
        return stockOutOrder(tenantId, order.getId()).orElseThrow(() -> notFound("出库单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalStockOutOrderDetailView confirmExternalGenericStockOut(
            String tenantId, String stockOutNo, ExternalGenericStockOutWrite command, String actorId) {
        Optional<InternalStockOutOrderDetailView> existing = stockOutOrderBySource(
                tenantId, command.connectorId(), command.sourceSystemCode(), command.sourceDocumentNo());
        if (existing.isPresent()) return existing.get();
        LocalDateTime now = now();
        InternalStockOutOrderEntity order = stockOutOrderEntity(tenantId, stockOutNo, command, actorId, now);
        try {
            getBaseMapper().insert(order);
            for (ExternalGenericStockOutLineWrite line : command.lines()) {
                insertStockOutLine(tenantId, order.getId(), line, actorId, now);
                if (command.affectStockBalance()) {
                    StockQuantityChange quantityChange = decreaseStockBalance(
                            tenantId, command.warehouseId(), line.productId(), line.productVariantId(),
                            line.quantity(), actorId, now);
                    insertStockFlow(tenantId, order.getId(), stockOutNo, command.warehouseId(),
                            command.stockOutTypeCode(), line, quantityChange, actorId, now);
                }
            }
        } catch (DataIntegrityViolationException exception) {
            return stockOutOrderBySource(
                    tenantId, command.connectorId(), command.sourceSystemCode(), command.sourceDocumentNo())
                    .orElseThrow(() -> conflict("出库单号或来源出库单号已存在，或出库引用数据无效"));
        }
        return stockOutOrder(tenantId, order.getId()).orElseThrow(() -> notFound("出库单不存在"));
    }

    private StockQuantityChange decreaseStockBalance(
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
        if (existing == null) throw conflict("出库仓库存不足，不能确认出库");
        BigDecimal before = zeroIfNull(existing.getAvailableQuantity());
        if (before.compareTo(quantity) < 0) throw conflict("出库仓库存不足，不能确认出库");
        BigDecimal after = before.subtract(quantity);
        int updated = stockBalanceMapper.update(null, Wrappers.<InternalStockBalanceEntity>lambdaUpdate()
                .set(InternalStockBalanceEntity::getAvailableQuantity, after)
                .set(InternalStockBalanceEntity::getRevision, existing.getRevision() + 1)
                .set(InternalStockBalanceEntity::getUpdatedBy, auditActor(actorId))
                .set(InternalStockBalanceEntity::getUpdatedTime, now)
                .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                .eq(InternalStockBalanceEntity::getId, existing.getId())
                .eq(InternalStockBalanceEntity::getRevision, existing.getRevision())
                .eq(InternalStockBalanceEntity::getDeleted, 0));
        if (updated != 1) throw conflict("库存余额已被其他单据修改，请重试销售出库");
        return new StockQuantityChange(before, after);
    }

    private void insertStockOutLine(
            String tenantId, Long stockOutOrderId, SalesStockOutLineWrite line, String actorId, LocalDateTime now) {
        InternalStockOutOrderLineEntity entity = new InternalStockOutOrderLineEntity();
        entity.setTenantId(tenantId);
        entity.setStockOutOrderId(stockOutOrderId);
        entity.setLineNo(line.lineNo());
        entity.setSalesOrderLineId(line.salesOrderLineId());
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setProductCodeSnapshot(line.productCode());
        entity.setVariantCodeSnapshot(line.variantCode());
        entity.setProductNameSnapshot(line.productName());
        entity.setUnitCode(line.unitCode());
        entity.setQuantity(line.quantity());
        entity.setRemark(line.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        lineMapper.insert(entity);
    }

    private void insertStockOutLine(
            String tenantId, Long stockOutOrderId, ExternalStockOutLineWrite line, String actorId,
            LocalDateTime now) {
        InternalStockOutOrderLineEntity entity = new InternalStockOutOrderLineEntity();
        entity.setTenantId(tenantId);
        entity.setStockOutOrderId(stockOutOrderId);
        entity.setLineNo(line.lineNo());
        entity.setSalesOrderLineId(line.salesOrderLineId());
        entity.setTransferOrderLineId(line.transferOrderLineId());
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setProductCodeSnapshot(line.productCode());
        entity.setVariantCodeSnapshot(line.variantCode());
        entity.setProductNameSnapshot(line.productName());
        entity.setUnitCode(line.unitCode());
        entity.setQuantity(line.quantity());
        entity.setRemark(line.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        lineMapper.insert(entity);
    }

    private void insertStockOutLine(
            String tenantId, Long stockOutOrderId, ExternalGenericStockOutLineWrite line, String actorId,
            LocalDateTime now) {
        InternalStockOutOrderLineEntity entity = new InternalStockOutOrderLineEntity();
        entity.setTenantId(tenantId);
        entity.setStockOutOrderId(stockOutOrderId);
        entity.setLineNo(line.lineNo());
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setProductCodeSnapshot(line.productCode());
        entity.setVariantCodeSnapshot(line.variantCode());
        entity.setProductNameSnapshot(line.productName());
        entity.setUnitCode(line.unitCode());
        entity.setQuantity(line.quantity());
        entity.setRemark(line.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        lineMapper.insert(entity);
    }

    private void insertStockFlow(
            String tenantId, Long stockOutOrderId, String stockOutNo, Long warehouseId, SalesStockOutLineWrite line,
            StockQuantityChange quantityChange, String actorId, LocalDateTime now) {
        InternalStockFlowEntity entity = new InternalStockFlowEntity();
        entity.setTenantId(tenantId);
        entity.setFlowNo(line.flowNo());
        entity.setWarehouseId(warehouseId);
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setBusinessTypeCode(ErpStockFlowType.SALES_OUT.code());
        entity.setBusinessOrderId(stockOutOrderId);
        entity.setBusinessOrderNo(stockOutNo);
        entity.setQuantityDelta(line.quantity().negate());
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

    private void insertStockFlow(
            String tenantId, Long stockOutOrderId, String stockOutNo, Long warehouseId,
            String stockOutTypeCode, ExternalStockOutLineWrite line,
            StockQuantityChange quantityChange, String actorId, LocalDateTime now) {
        InternalStockFlowEntity entity = new InternalStockFlowEntity();
        entity.setTenantId(tenantId);
        entity.setFlowNo(line.flowNo());
        entity.setWarehouseId(warehouseId);
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setBusinessTypeCode(ErpStockOutType.TRANSFER.code().equals(stockOutTypeCode)
                ? ErpStockFlowType.TRANSFER_OUT.code()
                : ErpStockFlowType.SALES_OUT.code());
        entity.setBusinessOrderId(stockOutOrderId);
        entity.setBusinessOrderNo(stockOutNo);
        entity.setQuantityDelta(line.quantity().negate());
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

    private void insertStockFlow(
            String tenantId, Long stockOutOrderId, String stockOutNo, Long warehouseId,
            String stockOutTypeCode, ExternalGenericStockOutLineWrite line,
            StockQuantityChange quantityChange, String actorId, LocalDateTime now) {
        InternalStockFlowEntity entity = new InternalStockFlowEntity();
        entity.setTenantId(tenantId);
        entity.setFlowNo(line.flowNo());
        entity.setWarehouseId(warehouseId);
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setBusinessTypeCode(stockFlowType(stockOutTypeCode));
        entity.setBusinessOrderId(stockOutOrderId);
        entity.setBusinessOrderNo(stockOutNo);
        entity.setQuantityDelta(line.quantity().negate());
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

    private InternalStockOutOrderEntity stockOutOrderEntity(
            String tenantId, String stockOutNo, SalesStockOutWrite command, String actorId, LocalDateTime now) {
        InternalStockOutOrderEntity entity = new InternalStockOutOrderEntity();
        entity.setTenantId(tenantId);
        entity.setStockOutNo(stockOutNo);
        entity.setStockOutTypeCode(command.stockOutTypeCode());
        entity.setWarehouseId(command.warehouseId());
        entity.setSalesOrderId(command.salesOrderId());
        entity.setSalesOrderNo(command.salesOrderNo());
        entity.setCustomerId(command.customerId());
        entity.setCustomerNameSnapshot(command.customerNameSnapshot());
        entity.setStatusCode(command.statusCode());
        entity.setStockOutTime(local(command.stockOutTime()));
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private InternalStockOutOrderEntity stockOutOrderEntity(
            String tenantId, String stockOutNo, ExternalStockOutWrite command, String actorId, LocalDateTime now) {
        InternalStockOutOrderEntity entity = new InternalStockOutOrderEntity();
        entity.setTenantId(tenantId);
        entity.setStockOutNo(stockOutNo);
        entity.setConnectorId(command.connectorId());
        entity.setSourceSystemCode(command.sourceSystemCode());
        entity.setSourceDocumentNo(command.sourceDocumentNo());
        entity.setStockOutTypeCode(command.stockOutTypeCode());
        entity.setWarehouseId(command.warehouseId());
        entity.setSalesOrderId(command.salesOrderId());
        entity.setSalesOrderNo(command.salesOrderNo());
        entity.setTransferOrderId(command.transferOrderId());
        entity.setTransferOrderNo(command.transferOrderNo());
        entity.setCustomerId(command.customerId());
        entity.setCustomerNameSnapshot(command.customerNameSnapshot());
        entity.setStatusCode(command.statusCode());
        entity.setStockOutTime(local(command.stockOutTime()));
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private InternalStockOutOrderEntity stockOutOrderEntity(
            String tenantId, String stockOutNo, ExternalGenericStockOutWrite command,
            String actorId, LocalDateTime now) {
        InternalStockOutOrderEntity entity = new InternalStockOutOrderEntity();
        entity.setTenantId(tenantId);
        entity.setStockOutNo(stockOutNo);
        entity.setConnectorId(command.connectorId());
        entity.setSourceSystemCode(command.sourceSystemCode());
        entity.setSourceDocumentNo(command.sourceDocumentNo());
        entity.setStockOutTypeCode(command.stockOutTypeCode());
        entity.setWarehouseId(command.warehouseId());
        entity.setStatusCode(command.statusCode());
        entity.setStockOutTime(local(command.stockOutTime()));
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(auditActor(actorId));
        entity.setCreatedTime(now);
        entity.setUpdatedBy(auditActor(actorId));
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private InternalStockOutOrderDetailView detail(
            String tenantId, InternalStockOutOrderEntity order, List<InternalStockOutOrderLineEntity> lines) {
        LineMetrics metrics = metrics(lines);
        return new InternalStockOutOrderDetailView(order.getId(), order.getStockOutNo(),
                order.getSourceSystemCode(), order.getSourceDocumentNo(), order.getStockOutTypeCode(),
                order.getWarehouseId(), warehouseNames(tenantId, Set.of(order.getWarehouseId())).get(order.getWarehouseId()),
                order.getSalesOrderId(), order.getSalesOrderNo(), order.getTransferOrderId(), order.getTransferOrderNo(),
                order.getCustomerId(), order.getCustomerNameSnapshot(), order.getStatusCode(),
                instant(order.getStockOutTime()), metrics.totalQuantity(), metrics.lineCount(),
                lines.stream().map(MybatisPlusStockOutOrderRepository::lineView).toList(), order.getRemark(),
                order.getRevision(), order.getCreatedBy(), instant(order.getCreatedTime()), order.getUpdatedBy(),
                instant(order.getUpdatedTime()));
    }

    private InternalStockOutOrderSummaryView summary(
            InternalStockOutOrderEntity order, Map<Long, String> warehouses, Map<Long, LineMetrics> metricsByOrder) {
        LineMetrics metrics = metricsByOrder.getOrDefault(order.getId(), LineMetrics.ZERO);
        return new InternalStockOutOrderSummaryView(order.getId(), order.getStockOutNo(),
                order.getSourceSystemCode(), order.getSourceDocumentNo(), order.getStockOutTypeCode(),
                order.getWarehouseId(), warehouses.get(order.getWarehouseId()), order.getSalesOrderId(),
                order.getSalesOrderNo(), order.getTransferOrderId(), order.getTransferOrderNo(),
                order.getCustomerId(), order.getCustomerNameSnapshot(), order.getStatusCode(),
                instant(order.getStockOutTime()), metrics.totalQuantity(), metrics.lineCount(),
                order.getRevision(), instant(order.getUpdatedTime()));
    }

    private Optional<InternalStockOutOrderEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalStockOutOrderEntity>lambdaQuery()
                .eq(InternalStockOutOrderEntity::getTenantId, tenantId)
                .eq(InternalStockOutOrderEntity::getId, id)
                .eq(InternalStockOutOrderEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private LambdaQueryWrapper<InternalStockOutOrderEntity> query(
            String tenantId, StockOutOrderSearchCriteria criteria) {
        LambdaQueryWrapper<InternalStockOutOrderEntity> query =
                Wrappers.<InternalStockOutOrderEntity>lambdaQuery()
                        .eq(InternalStockOutOrderEntity::getTenantId, tenantId)
                        .eq(InternalStockOutOrderEntity::getDeleted, 0);
        if (criteria.stockOutNo() != null) {
            query.and(value -> value
                    .like(InternalStockOutOrderEntity::getStockOutNo, criteria.stockOutNo())
                    .or()
                    .like(InternalStockOutOrderEntity::getSourceDocumentNo, criteria.stockOutNo())
                    .or()
                    .like(InternalStockOutOrderEntity::getSalesOrderNo, criteria.stockOutNo())
                    .or()
                    .like(InternalStockOutOrderEntity::getTransferOrderNo, criteria.stockOutNo())
                    .or()
                    .like(InternalStockOutOrderEntity::getCustomerNameSnapshot, criteria.stockOutNo())
                    .or()
                    .like(InternalStockOutOrderEntity::getRemark, criteria.stockOutNo()));
        }
        if (criteria.stockOutTypeCode() != null) {
            query.eq(InternalStockOutOrderEntity::getStockOutTypeCode, criteria.stockOutTypeCode());
        }
        if (criteria.warehouseId() != null) query.eq(InternalStockOutOrderEntity::getWarehouseId, criteria.warehouseId());
        if (criteria.salesOrderNo() != null) {
            query.like(InternalStockOutOrderEntity::getSalesOrderNo, criteria.salesOrderNo());
        }
        if (criteria.transferOrderNo() != null) {
            query.like(InternalStockOutOrderEntity::getTransferOrderNo, criteria.transferOrderNo());
        }
        if (criteria.customerName() != null) {
            query.like(InternalStockOutOrderEntity::getCustomerNameSnapshot, criteria.customerName());
        }
        if (criteria.statusCode() != null) query.eq(InternalStockOutOrderEntity::getStatusCode, criteria.statusCode());
        if (criteria.stockOutTimeFrom() != null) {
            query.ge(InternalStockOutOrderEntity::getStockOutTime, local(criteria.stockOutTimeFrom()));
        }
        if (criteria.stockOutTimeTo() != null) {
            query.le(InternalStockOutOrderEntity::getStockOutTime, local(criteria.stockOutTimeTo()));
        }
        return query;
    }

    private List<InternalStockOutOrderLineEntity> lines(String tenantId, Long stockOutOrderId) {
        return lineMapper.selectList(Wrappers.<InternalStockOutOrderLineEntity>lambdaQuery()
                .eq(InternalStockOutOrderLineEntity::getTenantId, tenantId)
                .eq(InternalStockOutOrderLineEntity::getStockOutOrderId, stockOutOrderId)
                .eq(InternalStockOutOrderLineEntity::getDeleted, 0)
                .orderByAsc(InternalStockOutOrderLineEntity::getLineNo)
                .orderByAsc(InternalStockOutOrderLineEntity::getId));
    }

    private Map<Long, LineMetrics> lineMetrics(String tenantId, Set<Long> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        return lineMapper.selectList(Wrappers.<InternalStockOutOrderLineEntity>lambdaQuery()
                        .eq(InternalStockOutOrderLineEntity::getTenantId, tenantId)
                        .in(InternalStockOutOrderLineEntity::getStockOutOrderId, orderIds)
                        .eq(InternalStockOutOrderLineEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.groupingBy(
                        InternalStockOutOrderLineEntity::getStockOutOrderId,
                        Collectors.collectingAndThen(Collectors.toList(), MybatisPlusStockOutOrderRepository::metrics)));
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

    private static Set<Long> ids(List<InternalStockOutOrderEntity> orders) {
        return orders.stream()
                .map(InternalStockOutOrderEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static InternalStockOutOrderLineView lineView(InternalStockOutOrderLineEntity line) {
        return new InternalStockOutOrderLineView(line.getId(), line.getLineNo(), line.getSalesOrderLineId(),
                line.getTransferOrderLineId(), line.getProductId(), line.getProductVariantId(),
                line.getProductCodeSnapshot(), line.getVariantCodeSnapshot(), line.getProductNameSnapshot(),
                line.getUnitCode(), line.getQuantity(), line.getRemark());
    }

    private static LineMetrics metrics(List<InternalStockOutOrderLineEntity> lines) {
        BigDecimal totalQuantity = lines.stream()
                .map(InternalStockOutOrderLineEntity::getQuantity)
                .map(MybatisPlusStockOutOrderRepository::zeroIfNull)
                .reduce(ZERO, BigDecimal::add);
        return new LineMetrics(totalQuantity, lines.size());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? ZERO : value;
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

    private static String stockFlowType(String stockOutTypeCode) {
        if (ErpStockOutType.TRANSFER.code().equals(stockOutTypeCode)) {
            return ErpStockFlowType.TRANSFER_OUT.code();
        }
        if (ErpStockOutType.PURCHASE_RETURN.code().equals(stockOutTypeCode)) {
            return ErpStockFlowType.PURCHASE_RETURN_OUT.code();
        }
        if (ErpStockOutType.INVENTORY_LOSS.code().equals(stockOutTypeCode)) {
            return ErpStockFlowType.INVENTORY_LOSS_OUT.code();
        }
        if (ErpStockOutType.JOINT_OPERATION.code().equals(stockOutTypeCode)) {
            return ErpStockFlowType.JOINT_OPERATION_OUT.code();
        }
        if (ErpStockOutType.OTHER.code().equals(stockOutTypeCode)) {
            return ErpStockFlowType.OTHER_OUT.code();
        }
        return ErpStockFlowType.SALES_OUT.code();
    }

    private record LineMetrics(BigDecimal totalQuantity, Integer lineCount) {
        private static final LineMetrics ZERO = new LineMetrics(BigDecimal.ZERO, 0);
    }

    private record StockQuantityChange(BigDecimal beforeQuantity, BigDecimal afterQuantity) {
    }
}
