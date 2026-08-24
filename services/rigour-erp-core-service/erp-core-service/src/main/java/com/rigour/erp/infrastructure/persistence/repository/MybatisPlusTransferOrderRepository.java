package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderLineView;
import com.rigour.erp.api.v1.model.InternalTransferOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpTransferOrderStore;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.ProductVariantSnapshot;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderLineSnapshot;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderLineWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderSearchCriteria;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderSnapshot;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferStockInLineWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferStockInWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferStockOutLineWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferStockOutWrite;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockBalanceEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockFlowEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockOutOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockOutOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalTransferOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalTransferOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockBalanceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockFlowMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockOutOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockOutOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalTransferOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalTransferOrderMapper;
import com.rigour.erp.domain.enums.ErpStockFlowType;
import com.rigour.erp.domain.enums.ErpStockInType;
import com.rigour.erp.domain.enums.ErpStockOutType;
import com.rigour.erp.domain.enums.ErpTransferStatus;
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

/** MyBatis-Plus 调拨单仓储；调拨出入库写统一出入库单，通过类型编码区分业务类型。 */
@Repository
public class MybatisPlusTransferOrderRepository
        extends ServiceImpl<InternalTransferOrderMapper, InternalTransferOrderEntity>
        implements ErpTransferOrderStore {
    private static final String ACTIVE = "ACTIVE";
    private static final String SUBMITTED = "SUBMITTED";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InternalTransferOrderLineMapper transferLineMapper;
    private final InternalStockOutOrderMapper stockOutOrderMapper;
    private final InternalStockOutOrderLineMapper stockOutLineMapper;
    private final InternalStockInOrderMapper stockInOrderMapper;
    private final InternalStockInOrderLineMapper stockInLineMapper;
    private final InternalStockBalanceMapper stockBalanceMapper;
    private final InternalStockFlowMapper stockFlowMapper;
    private final InternalInventoryWarehouseMapper warehouseMapper;
    private final InternalProductMapper productMapper;
    private final InternalProductVariantMapper variantMapper;
    private final Clock clock;

    public MybatisPlusTransferOrderRepository(
            InternalTransferOrderMapper mapper,
            InternalTransferOrderLineMapper transferLineMapper,
            InternalStockOutOrderMapper stockOutOrderMapper,
            InternalStockOutOrderLineMapper stockOutLineMapper,
            InternalStockInOrderMapper stockInOrderMapper,
            InternalStockInOrderLineMapper stockInLineMapper,
            InternalStockBalanceMapper stockBalanceMapper,
            InternalStockFlowMapper stockFlowMapper,
            InternalInventoryWarehouseMapper warehouseMapper,
            InternalProductMapper productMapper,
            InternalProductVariantMapper variantMapper,
            Clock erpClock) {
        this.baseMapper = mapper;
        this.transferLineMapper = transferLineMapper;
        this.stockOutOrderMapper = stockOutOrderMapper;
        this.stockOutLineMapper = stockOutLineMapper;
        this.stockInOrderMapper = stockInOrderMapper;
        this.stockInLineMapper = stockInLineMapper;
        this.stockBalanceMapper = stockBalanceMapper;
        this.stockFlowMapper = stockFlowMapper;
        this.warehouseMapper = warehouseMapper;
        this.productMapper = productMapper;
        this.variantMapper = variantMapper;
        this.clock = erpClock;
    }

    @Override
    public MasterDataPageView<InternalTransferOrderSummaryView> transferOrders(
            String tenantId, int begin, int step, TransferOrderSearchCriteria criteria) {
        InternalTransferOrderMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalTransferOrderEntity> page = mapper.selectList(query(tenantId, criteria)
                .orderByDesc(InternalTransferOrderEntity::getUpdatedTime)
                .orderByDesc(InternalTransferOrderEntity::getId)
                .last("LIMIT " + step + " OFFSET " + begin));
        Set<Long> transferIds = ids(page);
        Map<Long, LineMetrics> metrics = lineMetrics(tenantId, transferIds);
        Map<Long, String> warehouses = warehouseNames(tenantId, warehouseIds(page));
        Map<Long, StockOutDisplay> stockOutByTransferId = stockOutByTransferIds(tenantId, transferIds);
        Map<Long, StockInDisplay> stockInByTransferId = stockInByTransferIds(tenantId, transferIds);
        List<InternalTransferOrderSummaryView> items = page.stream()
                .map(order -> summary(order, warehouses, metrics, stockOutByTransferId, stockInByTransferId))
                .toList();
        return new MasterDataPageView<>(total, begin, step, items);
    }

    @Override
    public Optional<InternalTransferOrderDetailView> transferOrder(String tenantId, Long id) {
        return selectActive(tenantId, id).map(order -> detail(tenantId, order, lines(tenantId, id)));
    }

    @Override
    public boolean existsByTransferNo(String tenantId, String transferNo) {
        return getBaseMapper().selectCount(Wrappers.<InternalTransferOrderEntity>lambdaQuery()
                .eq(InternalTransferOrderEntity::getTenantId, tenantId)
                .eq(InternalTransferOrderEntity::getTransferNo, transferNo)) > 0;
    }

    @Override
    public boolean existsByStockOutNo(String tenantId, String stockOutNo) {
        return stockOutOrderMapper.selectCount(Wrappers.<InternalStockOutOrderEntity>lambdaQuery()
                .eq(InternalStockOutOrderEntity::getTenantId, tenantId)
                .eq(InternalStockOutOrderEntity::getStockOutNo, stockOutNo)) > 0;
    }

    @Override
    public boolean existsByStockInNo(String tenantId, String stockInNo) {
        return stockInOrderMapper.selectCount(Wrappers.<InternalStockInOrderEntity>lambdaQuery()
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
                variant.getVariantCode(), product.getProductName(), unitCode));
    }

    @Override
    public Optional<TransferOrderSnapshot> transferOrderForStockOut(String tenantId, Long transferOrderId) {
        InternalTransferOrderEntity order = selectActive(tenantId, transferOrderId).orElse(null);
        if (order == null) return Optional.empty();
        List<TransferOrderLineSnapshot> lineSnapshots = lines(tenantId, transferOrderId).stream()
                .map(MybatisPlusTransferOrderRepository::lineSnapshot)
                .toList();
        return Optional.of(new TransferOrderSnapshot(order.getId(), order.getTransferNo(),
                order.getSourceWarehouseId(), order.getTargetWarehouseId(), order.getStatusCode(),
                order.getRevision(), lineSnapshots));
    }

    @Override
    public Optional<TransferOrderSnapshot> transferOrderForStockIn(String tenantId, Long transferOrderId) {
        return transferOrderForStockOut(tenantId, transferOrderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalTransferOrderDetailView create(
            String tenantId, String transferNo, TransferOrderWrite command, String actorId) {
        LocalDateTime now = now();
        InternalTransferOrderEntity entity = transferOrderEntity(tenantId, transferNo, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
            insertLines(tenantId, entity.getId(), command.lines(), now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("调拨单号已存在或调拨引用数据无效");
        }
        return transferOrder(tenantId, entity.getId()).orElseThrow(() -> notFound("调拨单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalTransferOrderDetailView update(
            String tenantId, Long id, TransferOrderWrite command, String actorId) {
        requireEditable(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalTransferOrderEntity>lambdaUpdate()
                .set(InternalTransferOrderEntity::getSourceWarehouseId, command.sourceWarehouseId())
                .set(InternalTransferOrderEntity::getTargetWarehouseId, command.targetWarehouseId())
                .set(InternalTransferOrderEntity::getStatusCode, command.statusCode())
                .set(InternalTransferOrderEntity::getRemark, command.remark())
                .set(InternalTransferOrderEntity::getRevision, command.revision() + 1)
                .set(InternalTransferOrderEntity::getUpdatedBy, actorId)
                .set(InternalTransferOrderEntity::getUpdatedTime, now)
                .eq(InternalTransferOrderEntity::getTenantId, tenantId)
                .eq(InternalTransferOrderEntity::getId, id)
                .eq(InternalTransferOrderEntity::getRevision, command.revision())
                .eq(InternalTransferOrderEntity::getStatusCode, ErpTransferStatus.DRAFT.code())
                .eq(InternalTransferOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("调拨单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, now);
        insertLines(tenantId, id, command.lines(), now);
        return transferOrder(tenantId, id).orElseThrow(() -> notFound("调拨单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        requireEditable(tenantId, id);
        LocalDateTime now = now();
        int updated = getBaseMapper().update(null, Wrappers.<InternalTransferOrderEntity>lambdaUpdate()
                .set(InternalTransferOrderEntity::getDeleted, 1)
                .set(InternalTransferOrderEntity::getRevision, revision + 1)
                .set(InternalTransferOrderEntity::getUpdatedBy, actorId)
                .set(InternalTransferOrderEntity::getUpdatedTime, now)
                .eq(InternalTransferOrderEntity::getTenantId, tenantId)
                .eq(InternalTransferOrderEntity::getId, id)
                .eq(InternalTransferOrderEntity::getRevision, revision)
                .eq(InternalTransferOrderEntity::getStatusCode, ErpTransferStatus.DRAFT.code())
                .eq(InternalTransferOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("调拨单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalTransferOrderDetailView confirmStockOut(
            String tenantId, String stockOutNo, TransferStockOutWrite command, String actorId) {
        LocalDateTime now = now();
        InternalStockOutOrderEntity stockOutOrder = stockOutOrderEntity(tenantId, stockOutNo, command, actorId, now);
        try {
            stockOutOrderMapper.insert(stockOutOrder);
            for (TransferStockOutLineWrite line : command.lines()) {
                insertStockOutLine(tenantId, stockOutOrder.getId(), line, now);
                StockQuantityChange quantityChange = decreaseStockBalance(
                        tenantId, command.sourceWarehouseId(), line.productId(), line.productVariantId(),
                        line.quantity(), now);
                insertStockFlow(tenantId, stockOutOrder.getId(), stockOutNo, command.sourceWarehouseId(), line,
                        quantityChange, actorId, now);
            }
            updateTransferOrderStatus(tenantId, command, actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("出库单号已存在或出库引用数据无效");
        }
        return transferOrder(tenantId, command.transferOrderId()).orElseThrow(() -> notFound("调拨单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalTransferOrderDetailView confirmStockIn(
            String tenantId, String stockInNo, TransferStockInWrite command, String actorId) {
        LocalDateTime now = now();
        InternalStockInOrderEntity stockInOrder = transferStockInOrderEntity(
                tenantId, stockInNo, command, actorId, now);
        try {
            stockInOrderMapper.insert(stockInOrder);
            for (TransferStockInLineWrite line : command.lines()) {
                insertStockInLine(tenantId, stockInOrder.getId(), line, now);
                StockQuantityChange quantityChange = increaseStockBalance(
                        tenantId, command.targetWarehouseId(), line.productId(), line.productVariantId(),
                        line.quantity(), now);
                insertStockInFlow(tenantId, stockInOrder.getId(), stockInNo, command.targetWarehouseId(), line,
                        quantityChange, actorId, now);
            }
            updateTransferOrderStockInStatus(tenantId, command, actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("入库单号已存在或入库引用数据无效");
        }
        return transferOrder(tenantId, command.transferOrderId()).orElseThrow(() -> notFound("调拨单不存在"));
    }

    private void insertStockOutLine(
            String tenantId, Long stockOutOrderId, TransferStockOutLineWrite line, LocalDateTime now) {
        InternalStockOutOrderLineEntity entity = new InternalStockOutOrderLineEntity();
        entity.setTenantId(tenantId);
        entity.setStockOutOrderId(stockOutOrderId);
        entity.setLineNo(line.lineNo());
        entity.setTransferOrderLineId(line.transferOrderLineId());
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setProductCodeSnapshot(line.productCode());
        entity.setVariantCodeSnapshot(line.variantCode());
        entity.setProductNameSnapshot(line.productName());
        entity.setUnitCode(line.unitCode());
        entity.setQuantity(line.quantity());
        entity.setRemark(line.remark());
        entity.setCreatedTime(now);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        stockOutLineMapper.insert(entity);
    }

    private void insertStockInLine(
            String tenantId, Long stockInOrderId, TransferStockInLineWrite line, LocalDateTime now) {
        InternalStockInOrderLineEntity entity = new InternalStockInOrderLineEntity();
        entity.setTenantId(tenantId);
        entity.setStockInOrderId(stockInOrderId);
        entity.setLineNo(line.lineNo());
        entity.setTransferOrderLineId(line.transferOrderLineId());
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setProductCodeSnapshot(line.productCode());
        entity.setVariantCodeSnapshot(line.variantCode());
        entity.setProductNameSnapshot(line.productName());
        entity.setUnitCode(line.unitCode());
        entity.setQuantity(line.quantity());
        entity.setRemark(line.remark());
        entity.setCreatedTime(now);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        stockInLineMapper.insert(entity);
    }

    private StockQuantityChange decreaseStockBalance(
            String tenantId, Long warehouseId, Long productId, Long productVariantId, BigDecimal quantity,
            LocalDateTime now) {
        InternalStockBalanceEntity existing = stockBalanceMapper.selectOne(
                Wrappers.<InternalStockBalanceEntity>lambdaQuery()
                        .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                        .eq(InternalStockBalanceEntity::getWarehouseId, warehouseId)
                        .eq(InternalStockBalanceEntity::getProductId, productId)
                        .eq(InternalStockBalanceEntity::getProductVariantId, productVariantId)
                        .last("LIMIT 1"));
        if (existing == null) throw conflict("来源仓库存不足，不能确认调拨出库");
        BigDecimal before = zeroIfNull(existing.getAvailableQuantity());
        if (before.compareTo(quantity) < 0) throw conflict("来源仓库存不足，不能确认调拨出库");
        BigDecimal after = before.subtract(quantity);
        int updated = stockBalanceMapper.update(null, Wrappers.<InternalStockBalanceEntity>lambdaUpdate()
                .set(InternalStockBalanceEntity::getAvailableQuantity, after)
                .set(InternalStockBalanceEntity::getRevision, existing.getRevision() + 1)
                .set(InternalStockBalanceEntity::getUpdatedTime, now)
                .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                .eq(InternalStockBalanceEntity::getId, existing.getId())
                .eq(InternalStockBalanceEntity::getRevision, existing.getRevision()));
        if (updated != 1) throw conflict("库存余额已被其他单据修改，请重试调拨出库");
        return new StockQuantityChange(before, after);
    }

    private StockQuantityChange increaseStockBalance(
            String tenantId, Long warehouseId, Long productId, Long productVariantId, BigDecimal quantity,
            LocalDateTime now) {
        InternalStockBalanceEntity existing = stockBalanceMapper.selectOne(
                Wrappers.<InternalStockBalanceEntity>lambdaQuery()
                        .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                        .eq(InternalStockBalanceEntity::getWarehouseId, warehouseId)
                        .eq(InternalStockBalanceEntity::getProductId, productId)
                        .eq(InternalStockBalanceEntity::getProductVariantId, productVariantId)
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
            created.setCreatedTime(now);
            created.setUpdatedTime(now);
            stockBalanceMapper.insert(created);
            return new StockQuantityChange(ZERO, quantity);
        }
        BigDecimal before = zeroIfNull(existing.getAvailableQuantity());
        BigDecimal after = before.add(quantity);
        int updated = stockBalanceMapper.update(null, Wrappers.<InternalStockBalanceEntity>lambdaUpdate()
                .set(InternalStockBalanceEntity::getAvailableQuantity, after)
                .set(InternalStockBalanceEntity::getRevision, existing.getRevision() + 1)
                .set(InternalStockBalanceEntity::getUpdatedTime, now)
                .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                .eq(InternalStockBalanceEntity::getId, existing.getId())
                .eq(InternalStockBalanceEntity::getRevision, existing.getRevision()));
        if (updated != 1) throw conflict("库存余额已被其他单据修改，请重试调拨入库");
        return new StockQuantityChange(before, after);
    }

    private void insertStockFlow(
            String tenantId, Long stockOutOrderId, String stockOutNo, Long warehouseId, TransferStockOutLineWrite line,
            StockQuantityChange quantityChange, String actorId, LocalDateTime now) {
        InternalStockFlowEntity entity = new InternalStockFlowEntity();
        entity.setTenantId(tenantId);
        entity.setFlowNo(line.flowNo());
        entity.setWarehouseId(warehouseId);
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setBusinessTypeCode(ErpStockFlowType.TRANSFER_OUT.code());
        entity.setBusinessOrderId(stockOutOrderId);
        entity.setBusinessOrderNo(stockOutNo);
        entity.setQuantityDelta(line.quantity().negate());
        entity.setBeforeQuantity(quantityChange.beforeQuantity());
        entity.setAfterQuantity(quantityChange.afterQuantity());
        entity.setRemark(line.remark());
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        stockFlowMapper.insert(entity);
    }

    private void insertStockInFlow(
            String tenantId, Long stockInOrderId, String stockInNo, Long warehouseId, TransferStockInLineWrite line,
            StockQuantityChange quantityChange, String actorId, LocalDateTime now) {
        InternalStockFlowEntity entity = new InternalStockFlowEntity();
        entity.setTenantId(tenantId);
        entity.setFlowNo(line.flowNo());
        entity.setWarehouseId(warehouseId);
        entity.setProductId(line.productId());
        entity.setProductVariantId(line.productVariantId());
        entity.setBusinessTypeCode(ErpStockFlowType.TRANSFER_IN.code());
        entity.setBusinessOrderId(stockInOrderId);
        entity.setBusinessOrderNo(stockInNo);
        entity.setQuantityDelta(line.quantity());
        entity.setBeforeQuantity(quantityChange.beforeQuantity());
        entity.setAfterQuantity(quantityChange.afterQuantity());
        entity.setRemark(line.remark());
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        stockFlowMapper.insert(entity);
    }

    private void updateTransferOrderStatus(
            String tenantId, TransferStockOutWrite command, String actorId, LocalDateTime now) {
        int updated = getBaseMapper().update(null, Wrappers.<InternalTransferOrderEntity>lambdaUpdate()
                .set(InternalTransferOrderEntity::getStatusCode, command.nextTransferStatusCode())
                .set(InternalTransferOrderEntity::getStockOutTime, local(command.stockOutTime()))
                .set(InternalTransferOrderEntity::getRevision, command.transferRevision() + 1)
                .set(InternalTransferOrderEntity::getUpdatedBy, actorId)
                .set(InternalTransferOrderEntity::getUpdatedTime, now)
                .eq(InternalTransferOrderEntity::getTenantId, tenantId)
                .eq(InternalTransferOrderEntity::getId, command.transferOrderId())
                .eq(InternalTransferOrderEntity::getRevision, command.transferRevision())
                .eq(InternalTransferOrderEntity::getStatusCode, ErpTransferStatus.DRAFT.code())
                .eq(InternalTransferOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("调拨单已被其他人修改，请刷新后重试");
    }

    private void updateTransferOrderStockInStatus(
            String tenantId, TransferStockInWrite command, String actorId, LocalDateTime now) {
        int updated = getBaseMapper().update(null, Wrappers.<InternalTransferOrderEntity>lambdaUpdate()
                .set(InternalTransferOrderEntity::getStatusCode, command.nextTransferStatusCode())
                .set(InternalTransferOrderEntity::getStockInTime, local(command.stockInTime()))
                .set(InternalTransferOrderEntity::getRevision, command.transferRevision() + 1)
                .set(InternalTransferOrderEntity::getUpdatedBy, actorId)
                .set(InternalTransferOrderEntity::getUpdatedTime, now)
                .eq(InternalTransferOrderEntity::getTenantId, tenantId)
                .eq(InternalTransferOrderEntity::getId, command.transferOrderId())
                .eq(InternalTransferOrderEntity::getRevision, command.transferRevision())
                .eq(InternalTransferOrderEntity::getStatusCode, ErpTransferStatus.OUT_CONFIRMED.code())
                .eq(InternalTransferOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("调拨单已被其他人修改，请刷新后重试");
    }

    private InternalTransferOrderEntity requireEditable(String tenantId, Long id) {
        InternalTransferOrderEntity existing = selectActive(tenantId, id)
                .orElseThrow(() -> notFound("调拨单不存在"));
        if (!ErpTransferStatus.DRAFT.code().equals(existing.getStatusCode())) {
            throw conflict("调拨单已确认出库，不能直接修改或删除");
        }
        return existing;
    }

    private InternalTransferOrderEntity transferOrderEntity(
            String tenantId, String transferNo, TransferOrderWrite command, String actorId, LocalDateTime now) {
        InternalTransferOrderEntity entity = new InternalTransferOrderEntity();
        entity.setTenantId(tenantId);
        entity.setTransferNo(transferNo);
        entity.setSourceWarehouseId(command.sourceWarehouseId());
        entity.setTargetWarehouseId(command.targetWarehouseId());
        entity.setStatusCode(command.statusCode());
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private InternalStockOutOrderEntity stockOutOrderEntity(
            String tenantId, String stockOutNo, TransferStockOutWrite command, String actorId, LocalDateTime now) {
        InternalStockOutOrderEntity entity = new InternalStockOutOrderEntity();
        entity.setTenantId(tenantId);
        entity.setStockOutNo(stockOutNo);
        entity.setStockOutTypeCode(command.stockOutTypeCode());
        entity.setWarehouseId(command.sourceWarehouseId());
        entity.setTransferOrderId(command.transferOrderId());
        entity.setTransferOrderNo(command.transferNo());
        entity.setStatusCode(command.stockOutStatusCode());
        entity.setStockOutTime(local(command.stockOutTime()));
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private InternalStockInOrderEntity transferStockInOrderEntity(
            String tenantId, String stockInNo, TransferStockInWrite command, String actorId, LocalDateTime now) {
        InternalStockInOrderEntity entity = new InternalStockInOrderEntity();
        entity.setTenantId(tenantId);
        entity.setStockInNo(stockInNo);
        entity.setStockInTypeCode(command.stockInTypeCode());
        entity.setTransferOrderId(command.transferOrderId());
        entity.setTransferOrderNo(command.transferNo());
        entity.setWarehouseId(command.targetWarehouseId());
        entity.setStatusCode(command.stockInStatusCode());
        entity.setStockInTime(local(command.stockInTime()));
        entity.setRemark(command.remark());
        entity.setRevision(1);
        entity.setCreatedBy(actorId);
        entity.setCreatedTime(now);
        entity.setUpdatedBy(actorId);
        entity.setUpdatedTime(now);
        entity.setDeleted(0);
        return entity;
    }

    private void insertLines(String tenantId, Long orderId, List<TransferOrderLineWrite> lines,
                             LocalDateTime now) {
        for (TransferOrderLineWrite line : lines) {
            InternalTransferOrderLineEntity entity = new InternalTransferOrderLineEntity();
            entity.setTenantId(tenantId);
            entity.setTransferOrderId(orderId);
            entity.setLineNo(line.lineNo());
            entity.setProductId(line.productId());
            entity.setProductVariantId(line.productVariantId());
            entity.setProductCodeSnapshot(line.productCode());
            entity.setVariantCodeSnapshot(line.variantCode());
            entity.setProductNameSnapshot(line.productName());
            entity.setUnitCode(line.unitCode());
            entity.setQuantity(line.quantity());
            entity.setRemark(line.remark());
            entity.setCreatedTime(now);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            transferLineMapper.insert(entity);
        }
    }

    private void logicDeleteLines(String tenantId, Long orderId, LocalDateTime now) {
        transferLineMapper.update(null, Wrappers.<InternalTransferOrderLineEntity>lambdaUpdate()
                .set(InternalTransferOrderLineEntity::getDeleted, 1)
                .set(InternalTransferOrderLineEntity::getUpdatedTime, now)
                .eq(InternalTransferOrderLineEntity::getTenantId, tenantId)
                .eq(InternalTransferOrderLineEntity::getTransferOrderId, orderId)
                .eq(InternalTransferOrderLineEntity::getDeleted, 0));
    }

    private InternalTransferOrderDetailView detail(
            String tenantId, InternalTransferOrderEntity order, List<InternalTransferOrderLineEntity> lines) {
        Map<Long, String> warehouses = warehouseNames(tenantId,
                Set.of(order.getSourceWarehouseId(), order.getTargetWarehouseId()));
        StockOutDisplay stockOut = stockOutByTransferId(tenantId, order.getId()).orElse(StockOutDisplay.EMPTY);
        StockInDisplay stockIn = stockInByTransferId(tenantId, order.getId()).orElse(StockInDisplay.EMPTY);
        LineMetrics metrics = metrics(lines);
        return new InternalTransferOrderDetailView(order.getId(), order.getTransferNo(),
                order.getSourceWarehouseId(), warehouses.get(order.getSourceWarehouseId()),
                order.getTargetWarehouseId(), warehouses.get(order.getTargetWarehouseId()),
                order.getStatusCode(), instant(order.getStockOutTime()), instant(order.getStockInTime()),
                stockOut.stockOutOrderId(), stockOut.stockOutNo(), stockIn.stockInOrderId(), stockIn.stockInNo(),
                metrics.totalQuantity(),
                lines.stream().map(MybatisPlusTransferOrderRepository::lineView).toList(), order.getRemark(),
                order.getRevision(), order.getCreatedBy(), instant(order.getCreatedTime()), order.getUpdatedBy(),
                instant(order.getUpdatedTime()));
    }

    private InternalTransferOrderSummaryView summary(
            InternalTransferOrderEntity order, Map<Long, String> warehouses, Map<Long, LineMetrics> metricsByOrder,
            Map<Long, StockOutDisplay> stockOutByTransferId, Map<Long, StockInDisplay> stockInByTransferId) {
        LineMetrics metrics = metricsByOrder.getOrDefault(order.getId(), LineMetrics.ZERO);
        StockOutDisplay stockOut = stockOutByTransferId.getOrDefault(order.getId(), StockOutDisplay.EMPTY);
        StockInDisplay stockIn = stockInByTransferId.getOrDefault(order.getId(), StockInDisplay.EMPTY);
        return new InternalTransferOrderSummaryView(order.getId(), order.getTransferNo(),
                order.getSourceWarehouseId(), warehouses.get(order.getSourceWarehouseId()),
                order.getTargetWarehouseId(), warehouses.get(order.getTargetWarehouseId()),
                order.getStatusCode(), instant(order.getStockOutTime()), instant(order.getStockInTime()),
                stockOut.stockOutOrderId(), stockOut.stockOutNo(), stockIn.stockInOrderId(), stockIn.stockInNo(),
                metrics.totalQuantity(),
                metrics.lineCount(), order.getRevision(), instant(order.getUpdatedTime()));
    }

    private Optional<InternalTransferOrderEntity> selectActive(String tenantId, Long id) {
        return Optional.ofNullable(getBaseMapper().selectOne(Wrappers.<InternalTransferOrderEntity>lambdaQuery()
                .eq(InternalTransferOrderEntity::getTenantId, tenantId)
                .eq(InternalTransferOrderEntity::getId, id)
                .eq(InternalTransferOrderEntity::getDeleted, 0)
                .last("LIMIT 1")));
    }

    private LambdaQueryWrapper<InternalTransferOrderEntity> query(
            String tenantId, TransferOrderSearchCriteria criteria) {
        LambdaQueryWrapper<InternalTransferOrderEntity> query =
                Wrappers.<InternalTransferOrderEntity>lambdaQuery()
                        .eq(InternalTransferOrderEntity::getTenantId, tenantId)
                        .eq(InternalTransferOrderEntity::getDeleted, 0);
        if (criteria.transferNo() != null) {
            query.like(InternalTransferOrderEntity::getTransferNo, criteria.transferNo());
        }
        if (criteria.sourceWarehouseId() != null) {
            query.eq(InternalTransferOrderEntity::getSourceWarehouseId, criteria.sourceWarehouseId());
        }
        if (criteria.targetWarehouseId() != null) {
            query.eq(InternalTransferOrderEntity::getTargetWarehouseId, criteria.targetWarehouseId());
        }
        if (criteria.statusCode() != null) {
            query.eq(InternalTransferOrderEntity::getStatusCode, criteria.statusCode());
        }
        if (criteria.stockOutTimeFrom() != null) {
            query.ge(InternalTransferOrderEntity::getStockOutTime, local(criteria.stockOutTimeFrom()));
        }
        if (criteria.stockOutTimeTo() != null) {
            query.le(InternalTransferOrderEntity::getStockOutTime, local(criteria.stockOutTimeTo()));
        }
        return query;
    }

    private List<InternalTransferOrderLineEntity> lines(String tenantId, Long transferOrderId) {
        return transferLineMapper.selectList(Wrappers.<InternalTransferOrderLineEntity>lambdaQuery()
                .eq(InternalTransferOrderLineEntity::getTenantId, tenantId)
                .eq(InternalTransferOrderLineEntity::getTransferOrderId, transferOrderId)
                .eq(InternalTransferOrderLineEntity::getDeleted, 0)
                .orderByAsc(InternalTransferOrderLineEntity::getLineNo)
                .orderByAsc(InternalTransferOrderLineEntity::getId));
    }

    private Map<Long, LineMetrics> lineMetrics(String tenantId, Set<Long> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        return transferLineMapper.selectList(Wrappers.<InternalTransferOrderLineEntity>lambdaQuery()
                        .eq(InternalTransferOrderLineEntity::getTenantId, tenantId)
                        .in(InternalTransferOrderLineEntity::getTransferOrderId, orderIds)
                        .eq(InternalTransferOrderLineEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.groupingBy(
                        InternalTransferOrderLineEntity::getTransferOrderId,
                        Collectors.collectingAndThen(Collectors.toList(), MybatisPlusTransferOrderRepository::metrics)));
    }

    private Map<Long, StockOutDisplay> stockOutByTransferIds(String tenantId, Set<Long> transferIds) {
        if (transferIds.isEmpty()) return Map.of();
        return stockOutOrderMapper.selectList(Wrappers.<InternalStockOutOrderEntity>lambdaQuery()
                        .eq(InternalStockOutOrderEntity::getTenantId, tenantId)
                        .in(InternalStockOutOrderEntity::getTransferOrderId, transferIds)
                        .eq(InternalStockOutOrderEntity::getStockOutTypeCode, ErpStockOutType.TRANSFER.code())
                        .eq(InternalStockOutOrderEntity::getDeleted, 0)
                        .orderByDesc(InternalStockOutOrderEntity::getId))
                .stream()
                .collect(Collectors.toMap(InternalStockOutOrderEntity::getTransferOrderId,
                        order -> new StockOutDisplay(order.getId(), order.getStockOutNo()), (first, ignored) -> first));
    }

    private Optional<StockOutDisplay> stockOutByTransferId(String tenantId, Long transferId) {
        InternalStockOutOrderEntity stockOut = stockOutOrderMapper.selectOne(
                Wrappers.<InternalStockOutOrderEntity>lambdaQuery()
                        .eq(InternalStockOutOrderEntity::getTenantId, tenantId)
                        .eq(InternalStockOutOrderEntity::getTransferOrderId, transferId)
                        .eq(InternalStockOutOrderEntity::getStockOutTypeCode, ErpStockOutType.TRANSFER.code())
                        .eq(InternalStockOutOrderEntity::getDeleted, 0)
                        .orderByDesc(InternalStockOutOrderEntity::getId)
                        .last("LIMIT 1"));
        return stockOut == null ? Optional.empty()
                : Optional.of(new StockOutDisplay(stockOut.getId(), stockOut.getStockOutNo()));
    }

    private Map<Long, StockInDisplay> stockInByTransferIds(String tenantId, Set<Long> transferIds) {
        if (transferIds.isEmpty()) return Map.of();
        return stockInOrderMapper.selectList(Wrappers.<InternalStockInOrderEntity>lambdaQuery()
                        .eq(InternalStockInOrderEntity::getTenantId, tenantId)
                        .in(InternalStockInOrderEntity::getTransferOrderId, transferIds)
                        .eq(InternalStockInOrderEntity::getStockInTypeCode, ErpStockInType.TRANSFER.code())
                        .eq(InternalStockInOrderEntity::getDeleted, 0)
                        .orderByDesc(InternalStockInOrderEntity::getId))
                .stream()
                .collect(Collectors.toMap(InternalStockInOrderEntity::getTransferOrderId,
                        order -> new StockInDisplay(order.getId(), order.getStockInNo()), (first, ignored) -> first));
    }

    private Optional<StockInDisplay> stockInByTransferId(String tenantId, Long transferId) {
        InternalStockInOrderEntity stockIn = stockInOrderMapper.selectOne(
                Wrappers.<InternalStockInOrderEntity>lambdaQuery()
                        .eq(InternalStockInOrderEntity::getTenantId, tenantId)
                        .eq(InternalStockInOrderEntity::getTransferOrderId, transferId)
                        .eq(InternalStockInOrderEntity::getStockInTypeCode, ErpStockInType.TRANSFER.code())
                        .eq(InternalStockInOrderEntity::getDeleted, 0)
                        .orderByDesc(InternalStockInOrderEntity::getId)
                        .last("LIMIT 1"));
        return stockIn == null ? Optional.empty()
                : Optional.of(new StockInDisplay(stockIn.getId(), stockIn.getStockInNo()));
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

    private static Set<Long> ids(List<InternalTransferOrderEntity> orders) {
        return orders.stream()
                .map(InternalTransferOrderEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Long> warehouseIds(List<InternalTransferOrderEntity> orders) {
        return orders.stream()
                .flatMap(order -> java.util.stream.Stream.of(
                        order.getSourceWarehouseId(), order.getTargetWarehouseId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static InternalTransferOrderLineView lineView(InternalTransferOrderLineEntity line) {
        return new InternalTransferOrderLineView(line.getId(), line.getLineNo(), line.getProductId(),
                line.getProductVariantId(), line.getProductCodeSnapshot(), line.getVariantCodeSnapshot(),
                line.getProductNameSnapshot(), line.getUnitCode(), line.getQuantity(), line.getRemark());
    }

    private static TransferOrderLineSnapshot lineSnapshot(InternalTransferOrderLineEntity line) {
        return new TransferOrderLineSnapshot(line.getId(), line.getLineNo(), line.getProductId(),
                line.getProductVariantId(), line.getProductCodeSnapshot(), line.getVariantCodeSnapshot(),
                line.getProductNameSnapshot(), line.getUnitCode(), line.getQuantity(), line.getRemark());
    }

    private static LineMetrics metrics(List<InternalTransferOrderLineEntity> lines) {
        BigDecimal totalQuantity = lines.stream()
                .map(InternalTransferOrderLineEntity::getQuantity)
                .map(MybatisPlusTransferOrderRepository::zeroIfNull)
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

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private record LineMetrics(BigDecimal totalQuantity, Integer lineCount) {
        private static final LineMetrics ZERO = new LineMetrics(BigDecimal.ZERO, 0);
    }

    private record StockQuantityChange(BigDecimal beforeQuantity, BigDecimal afterQuantity) {
    }

    private record StockOutDisplay(Long stockOutOrderId, String stockOutNo) {
        private static final StockOutDisplay EMPTY = new StockOutDisplay(null, null);
    }

    private record StockInDisplay(Long stockInOrderId, String stockInNo) {
        private static final StockInDisplay EMPTY = new StockInDisplay(null, null);
    }
}
