package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.erp.application.port.out.SupplyDataStore;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.erp.domain.model.supply.InventoryBalance;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.Supplier;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.erp.domain.model.supply.Warehouse;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProcurementOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalProductVariantEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalPurchaseReturnOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalPurchaseReturnOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockBalanceEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalStockInOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.InternalSupplierProfileEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncLockEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncRunEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterSourceBindingEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProcurementOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalProductVariantMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalPurchaseReturnOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalPurchaseReturnOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockBalanceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalStockInOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.InternalSupplierProfileMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncLockMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * ERP 供应链订货宝同步仓储。
 *
 * <p>订货宝只作为外部来源，数据落到 ERP 自研采购、仓库、库存、入出库业务表。</p>
 */
@Repository
public class MybatisPlusSupplyDataRepository implements SupplyDataStore {
    private static final String SOURCE_SYSTEM = "DINGHUOBAO";
    private static final String SYNC_ACTOR = "DHB_SYNC";
    private static final String TRIGGER_MANUAL = "MANUAL";
    private static final String TRIGGER_SCHEDULED = "SCHEDULED";
    private static final String PRESENT = "PRESENT";
    private static final String SOURCE_ABSENT = "SOURCE_ABSENT";
    private static final long RUN_LEASE_MINUTES = 30;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final InternalSupplierProfileMapper supplierMapper;
    private final InternalInventoryWarehouseMapper warehouseMapper;
    private final InternalProductMapper productMapper;
    private final InternalProductVariantMapper variantMapper;
    private final InternalProcurementOrderMapper procurementMapper;
    private final InternalProcurementOrderLineMapper procurementLineMapper;
    private final InternalPurchaseReturnOrderMapper purchaseReturnMapper;
    private final InternalPurchaseReturnOrderLineMapper purchaseReturnLineMapper;
    private final InternalStockInOrderMapper stockInMapper;
    private final InternalStockInOrderLineMapper stockInLineMapper;
    private final InternalStockBalanceMapper stockBalanceMapper;
    private final MasterSourceBindingMapper bindingMapper;
    private final MasterDataSyncRunMapper syncRunMapper;
    private final MasterDataSyncLockMapper syncLockMapper;
    private final BusinessCodeGenerator codeGenerator = new BusinessCodeGenerator();
    private final Clock clock;

    public MybatisPlusSupplyDataRepository(
            InternalSupplierProfileMapper supplierMapper,
            InternalInventoryWarehouseMapper warehouseMapper,
            InternalProductMapper productMapper,
            InternalProductVariantMapper variantMapper,
            InternalProcurementOrderMapper procurementMapper,
            InternalProcurementOrderLineMapper procurementLineMapper,
            InternalPurchaseReturnOrderMapper purchaseReturnMapper,
            InternalPurchaseReturnOrderLineMapper purchaseReturnLineMapper,
            InternalStockInOrderMapper stockInMapper,
            InternalStockInOrderLineMapper stockInLineMapper,
            InternalStockBalanceMapper stockBalanceMapper,
            MasterSourceBindingMapper bindingMapper,
            MasterDataSyncRunMapper syncRunMapper,
            MasterDataSyncLockMapper syncLockMapper,
            Clock clock) {
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.productMapper = productMapper;
        this.variantMapper = variantMapper;
        this.procurementMapper = procurementMapper;
        this.procurementLineMapper = procurementLineMapper;
        this.purchaseReturnMapper = purchaseReturnMapper;
        this.purchaseReturnLineMapper = purchaseReturnLineMapper;
        this.stockInMapper = stockInMapper;
        this.stockInLineMapper = stockInLineMapper;
        this.stockBalanceMapper = stockBalanceMapper;
        this.bindingMapper = bindingMapper;
        this.syncRunMapper = syncRunMapper;
        this.syncLockMapper = syncLockMapper;
        this.clock = clock;
    }

    @Override
    public List<String> sourceProductCodes(String tenantId) {
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", "PRODUCT_SPU")
                        .eq("source_presence", PRESENT)
                        .isNotNull("source_code"))
                .stream().map(binding -> binding.sourceCode)
                .filter(value -> !missing(value))
                .map(String::strip)
                .distinct()
                .toList();
    }

    @Override
    @Transactional
    public UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                         SupplyDataObjectType type, int maxPages) {
        return startRun(tenantId, connectorId, actorId, type, maxPages, TRIGGER_MANUAL);
    }

    @Override
    @Transactional
    public UUID startScheduledRun(String tenantId, UUID connectorId, UUID actorId,
                                  SupplyDataObjectType type, int maxPages) {
        return startRun(tenantId, connectorId, actorId, type, maxPages, TRIGGER_SCHEDULED);
    }

    @Override
    @Transactional
    public ImportResult importSupplier(String tenantId, UUID runId, Supplier item) {
        if (missing(item.sourceId()) || missing(item.name())) return ImportResult.oneRejected();
        UpsertResult result = upsertSupplier(tenantId, runId, item.sourceId(), item.code(),
                item.name(), item.contactName(), firstText(item.mobile(), item.phone()),
                item.address(), item.bankName(), item.bankAccount(), item.remark(),
                item.payloadHash(), "ACTIVE");
        return result.result();
    }

    @Override
    @Transactional
    public ImportResult importWarehouse(String tenantId, UUID runId, Warehouse item) {
        if (missing(item.sourceId()) || missing(item.name())) return ImportResult.oneRejected();
        UpsertResult result = upsertWarehouse(tenantId, runId, item.sourceId(), item.code(),
                item.name(), item.defaultFlag(), item.address(), null, item.phone(),
                item.remark(), item.payloadHash(), warehouseStatus(item.sourceStatus()));
        return result.result();
    }

    @Override
    @Transactional
    public ImportResult importPurchaseOrder(String tenantId, UUID runId, PurchaseOrder item) {
        if (missing(item.sourceId()) || missing(item.number())) return ImportResult.oneRejected();
        LocalDateTime now = now();
        UpsertResult supplier = ensureSupplier(tenantId, runId, item.supplierSourceId(),
                item.supplierCode(), item.supplierName(), now);
        UpsertResult warehouse = ensureWarehouse(tenantId, runId, item.warehouseSourceId(),
                item.warehouseCode(), item.warehouseName(), now);
        if (supplier.id() == null || warehouse.id() == null) return ImportResult.oneRejected();
        MasterSourceBindingEntity binding = binding(tenantId, "PURCHASE_ORDER", item.sourceId());
        boolean changed = changed(binding, item.payloadHash());
        Long id = longTargetId(binding);
        InternalProcurementOrderEntity entity = id == null ? null : procurementMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        if (created) {
            entity = new InternalProcurementOrderEntity();
            entity.setTenantId(tenantId);
            entity.setProcurementNo(uniqueProcurementNo(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(sourceTime(item.sourceCreatedAt(), now));
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || !Objects.equals(entity.getTotalAmount(), amount(item.totalAmount()))))) {
            entity.setSupplierId(supplier.id());
            entity.setTargetWarehouseId(warehouse.id());
            entity.setStatusCode(procurementStatus(item.sourceStatus(), item.sourceStatusName()));
            entity.setExpectedArrivalTime(sourceTime(item.deliveryAt(), null));
            entity.setTotalQuantity(amount(item.goodsCount()));
            entity.setTotalAmount(amount(item.totalAmount()));
            entity.setRemark(firstText(item.remark(), item.internalCommunication()));
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) procurementMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                procurementMapper.updateById(entity);
            }
            replaceProcurementLines(tenantId, runId, entity.getId(), item, now);
        }
        upsertBinding(tenantId, runId, "PURCHASE_ORDER", item.sourceId(), "PROCUREMENT_ORDER",
                entity.getId(), item.number(), item.supplierName(), item.sourceStatus(), null,
                item.payloadHash(), now);
        return importResult(binding, created, changed);
    }

    @Override
    @Transactional
    public ImportResult importWarehousingReceipt(String tenantId, UUID runId,
                                                 WarehousingReceipt item) {
        if (missing(item.sourceId()) || missing(item.number())) return ImportResult.oneRejected();
        LocalDateTime now = now();
        UpsertResult warehouse = ensureWarehouse(tenantId, runId, item.warehouseSourceId(),
                null, item.warehouseName(), now);
        UpsertResult supplier = ensureSupplier(tenantId, runId, item.supplierSourceId(),
                null, item.supplierName(), now);
        if (warehouse.id() == null) return ImportResult.oneRejected();
        MasterSourceBindingEntity binding = binding(tenantId, "WAREHOUSING_RECEIPT", item.sourceId());
        boolean changed = changed(binding, item.payloadHash());
        Long id = longTargetId(binding);
        InternalStockInOrderEntity entity = id == null ? null : stockInMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        if (created) {
            entity = new InternalStockInOrderEntity();
            entity.setTenantId(tenantId);
            entity.setStockInNo(uniqueStockInNo(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(sourceTime(item.sourceCreatedAt(), now));
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy()) && changed)) {
            entity.setStockInTypeCode("PURCHASE");
            entity.setWarehouseId(warehouse.id());
            entity.setSupplierId(supplier.id());
            entity.setStatusCode(stockStatus(item.sourceStatus(), item.sourceStatusName()));
            entity.setStockInTime(sourceTime(item.storageAt(), null));
            entity.setRemark(item.remark());
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) stockInMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                stockInMapper.updateById(entity);
            }
            replaceStockInLines(tenantId, runId, entity.getId(), item, now);
        }
        upsertBinding(tenantId, runId, "WAREHOUSING_RECEIPT", item.sourceId(), "STOCK_IN_ORDER",
                entity.getId(), item.number(), item.warehouseName(), item.sourceStatus(), null,
                item.payloadHash(), now);
        return importResult(binding, created, changed);
    }

    @Override
    @Transactional
    public ImportResult importPurchaseReturn(String tenantId, UUID runId, PurchaseReturn item) {
        if (missing(item.sourceId()) || missing(item.number())) return ImportResult.oneRejected();
        LocalDateTime now = now();
        UpsertResult supplier = ensureSupplier(tenantId, runId, item.supplierSourceId(),
                item.supplierCode(), item.supplierName(), now);
        UpsertResult warehouse = ensureWarehouse(tenantId, runId, item.warehouseSourceId(),
                item.warehouseCode(), item.warehouseName(), now);
        if (supplier.id() == null || warehouse.id() == null) return ImportResult.oneRejected();
        MasterSourceBindingEntity binding = binding(tenantId, "PURCHASE_RETURN", item.sourceId());
        boolean changed = changed(binding, item.payloadHash());
        Long id = longTargetId(binding);
        InternalPurchaseReturnOrderEntity entity = id == null ? null : purchaseReturnMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        if (created) {
            entity = new InternalPurchaseReturnOrderEntity();
            entity.setTenantId(tenantId);
            entity.setPurchaseReturnNo(uniquePurchaseReturnNo(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(sourceTime(item.sourceCreatedAt(), now));
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy()) && changed)) {
            entity.setSupplierId(supplier.id());
            entity.setWarehouseId(warehouse.id());
            entity.setOperatorStaffCode(null);
            entity.setOperatorStaffNameSnapshot(blank(item.staffName()));
            entity.setStatusCode(purchaseReturnStatus(item.sourceStatus(), item.sourceStatusName()));
            entity.setTotalQuantity(totalReturnQuantity(item));
            entity.setTotalAmount(amount(item.returnAmount()));
            entity.setDiscountAmount(amount(item.discountAmount()));
            entity.setReturnTime(sourceTime(item.sendAt(), null));
            entity.setContactName(blank(item.contactName()));
            entity.setContactPhone(blank(item.contactPhone()));
            entity.setContactAddress(blank(item.contactAddress()));
            entity.setReason(blank(item.reason()));
            entity.setRemark(returnRemark(item));
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) purchaseReturnMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                purchaseReturnMapper.updateById(entity);
            }
            replacePurchaseReturnLines(tenantId, runId, entity.getId(), item, now);
        }
        upsertBinding(tenantId, runId, "PURCHASE_RETURN", item.sourceId(), "PURCHASE_RETURN_ORDER",
                entity.getId(), item.number(), item.supplierName(), item.sourceStatus(), null,
                item.payloadHash(), now);
        return importResult(binding, created, changed);
    }

    @Override
    @Transactional
    public ImportResult importInventory(String tenantId, UUID runId, InventoryBalance item) {
        String sourceId = inventorySourceId(item);
        if (missing(sourceId) || missing(item.goodsCode()) || missing(item.warehouseCode())) {
            return ImportResult.oneRejected();
        }
        LocalDateTime now = now();
        UpsertResult product = ensureProduct(tenantId, runId, item.goodsGuid(), item.goodsCode(),
                item.goodsName(), now);
        UpsertResult variant = ensureVariant(tenantId, runId, product.id(), inventoryVariantSourceId(item),
                item.goodsCode(), optionSnapshot(item), now);
        UpsertResult warehouse = ensureWarehouse(tenantId, runId, item.warehouseGuid(), item.warehouseCode(),
                item.warehouseName(), now);
        if (product.id() == null || variant.id() == null || warehouse.id() == null) {
            return ImportResult.oneRejected();
        }
        MasterSourceBindingEntity binding = binding(tenantId, "INVENTORY_BALANCE", sourceId);
        boolean changed = changed(binding, item.payloadHash());
        InternalStockBalanceEntity entity = stockBalanceMapper.selectOne(
                Wrappers.<InternalStockBalanceEntity>lambdaQuery()
                        .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                        .eq(InternalStockBalanceEntity::getWarehouseId, warehouse.id())
                        .eq(InternalStockBalanceEntity::getProductId, product.id())
                        .eq(InternalStockBalanceEntity::getProductVariantId, variant.id())
                        .last("LIMIT 1"));
        boolean created = entity == null;
        if (created) {
            entity = new InternalStockBalanceEntity();
            entity.setTenantId(tenantId);
            entity.setWarehouseId(warehouse.id());
            entity.setProductId(product.id());
            entity.setProductVariantId(variant.id());
            entity.setRevision(1);
            entity.setCreatedTime(now);
        }
        if (created || changed || !Objects.equals(entity.getAvailableQuantity(), amount(item.availableQuantity()))) {
            entity.setAvailableQuantity(amount(item.availableQuantity()));
            entity.setLockedQuantity(ZERO);
            entity.setInTransitQuantity(ZERO);
            entity.setUpdatedTime(now);
            if (created) stockBalanceMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                stockBalanceMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "INVENTORY_BALANCE", sourceId, "STOCK_BALANCE",
                entity.getId(), item.goodsCode(), item.goodsName(), null, null,
                item.payloadHash(), now);
        return importResult(binding, created, changed);
    }

    @Override
    @Transactional
    public void reconcileSourcePresence(String tenantId, UUID runId,
                                        Map<String, Set<String>> seenSourceIds) {
        LocalDateTime now = now();
        seenSourceIds.forEach((sourceType, values) -> {
            Set<String> seen = values == null ? Set.of() : values;
            List<MasterSourceBindingEntity> bindings = bindingMapper.selectList(
                    Wrappers.<MasterSourceBindingEntity>query()
                            .eq("tenant_id", tenantId)
                            .eq("source_system", SOURCE_SYSTEM)
                            .eq("source_object_type", sourceType));
            for (MasterSourceBindingEntity binding : bindings) {
                boolean present = seen.contains(binding.sourceObjectId);
                String desired = present ? PRESENT : SOURCE_ABSENT;
                if (Objects.equals(binding.sourcePresence, desired)) continue;
                binding.sourcePresence = desired;
                binding.sourceAbsentAt = present ? null : now;
                binding.lastSyncRunId = text(runId);
                binding.version = nextVersion(binding.version);
                binding.updatedAt = now;
                bindingMapper.updateById(binding);
            }
        });
    }

    @Override
    @Transactional
    public void completeRunWithSourcePresence(String tenantId, UUID runId,
                                              Map<String, Set<String>> seenSourceIds,
                                              RunStatistics statistics) {
        requireRunningRunForUpdate(tenantId, runId);
        reconcileSourcePresence(tenantId, runId, seenSourceIds);
        completeRun(tenantId, runId, statistics);
    }

    @Override
    @Transactional
    public void completeRun(String tenantId, UUID runId, RunStatistics statistics) {
        finishRun(tenantId, runId, statistics.dictionaryAudit().unmapped() == 0
                ? "SUCCEEDED" : "SUCCEEDED_WITH_WARNINGS", statistics, null, null);
    }

    @Override
    @Transactional
    public void failRun(String tenantId, UUID runId, RunStatistics statistics, RuntimeException error) {
        String message = error.getMessage();
        if (message != null && message.length() > 2000) message = message.substring(0, 2000);
        finishRun(tenantId, runId, "FAILED", statistics,
                error.getClass().getSimpleName(), message);
    }

    @Override
    @Transactional
    public void heartbeatRun(String tenantId, UUID runId) {
        LocalDateTime now = now();
        syncRunMapper.update(null, Wrappers.<MasterDataSyncRunEntity>update()
                .eq("tenant_id", tenantId).eq("id", text(runId)).eq("status", "RUNNING")
                .set("updated_at", now));
        syncLockMapper.update(null, Wrappers.<MasterDataSyncLockEntity>update()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("run_id", text(runId))
                .set("expires_at", now.plus(Duration.ofMinutes(RUN_LEASE_MINUTES))));
    }

    private void replaceProcurementLines(String tenantId, UUID runId, Long orderId,
                                         PurchaseOrder item, LocalDateTime now) {
        procurementLineMapper.delete(Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderLineEntity::getProcurementOrderId, orderId));
        int lineNo = 1;
        for (PurchaseOrder.Line line : item.lines()) {
            UpsertResult product = ensureProduct(tenantId, runId, line.sourceGoodsGuid(),
                    line.goodsCode(), line.goodsName(), now);
            UpsertResult variant = ensureVariant(tenantId, runId, product.id(),
                    firstText(line.optionsId(), line.optionsGoodsCode()), line.optionsGoodsCode(),
                    line.optionsSummary(), now);
            if (product.id() == null || variant.id() == null) continue;
            InternalProductEntity productEntity = productMapper.selectById(product.id());
            InternalProductVariantEntity variantEntity = variantMapper.selectById(variant.id());
            InternalProcurementOrderLineEntity entity = new InternalProcurementOrderLineEntity();
            entity.setTenantId(tenantId);
            entity.setProcurementOrderId(orderId);
            entity.setLineNo(lineNo++);
            entity.setProductId(product.id());
            entity.setProductVariantId(variant.id());
            entity.setProductCodeSnapshot(productEntity == null ? null : productEntity.getProductCode());
            entity.setVariantCodeSnapshot(variantEntity == null ? null : variantEntity.getVariantCode());
            entity.setProductNameSnapshot(firstText(line.goodsName(), productEntity == null ? null
                    : productEntity.getProductName()));
            entity.setUnitCode(internalUnitCode(firstText(line.unitCode(), line.unitName())));
            entity.setQuantity(amount(firstAmount(line.unitQuantity(), line.baseQuantity())));
            entity.setUnitPrice(amount(line.unitPrice()));
            entity.setLineAmount(amount(entity.getQuantity().multiply(entity.getUnitPrice())));
            entity.setReceivedQuantity(amount(line.warehousedQuantity()));
            entity.setRemark(line.remark());
            entity.setCreatedTime(now);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            procurementLineMapper.insert(entity);
        }
    }

    private void replaceStockInLines(String tenantId, UUID runId, Long stockInId,
                                     WarehousingReceipt item, LocalDateTime now) {
        stockInLineMapper.delete(Wrappers.<InternalStockInOrderLineEntity>lambdaQuery()
                .eq(InternalStockInOrderLineEntity::getTenantId, tenantId)
                .eq(InternalStockInOrderLineEntity::getStockInOrderId, stockInId));
        int lineNo = 1;
        for (WarehousingReceipt.Line line : item.lines()) {
            UpsertResult product = ensureProduct(tenantId, runId, line.sourceGoodsId(),
                    line.goodsCode(), line.goodsName(), now);
            UpsertResult variant = ensureVariant(tenantId, runId, product.id(),
                    firstText(line.optionsId(), line.optionsGoodsCode()), line.optionsGoodsCode(),
                    line.optionsSummary(), now);
            if (product.id() == null || variant.id() == null) continue;
            InternalProductEntity productEntity = productMapper.selectById(product.id());
            InternalProductVariantEntity variantEntity = variantMapper.selectById(variant.id());
            InternalStockInOrderLineEntity entity = new InternalStockInOrderLineEntity();
            entity.setTenantId(tenantId);
            entity.setStockInOrderId(stockInId);
            entity.setLineNo(lineNo++);
            entity.setProductId(product.id());
            entity.setProductVariantId(variant.id());
            entity.setProductCodeSnapshot(productEntity == null ? null : productEntity.getProductCode());
            entity.setVariantCodeSnapshot(variantEntity == null ? null : variantEntity.getVariantCode());
            entity.setProductNameSnapshot(firstText(line.goodsName(), productEntity == null ? null
                    : productEntity.getProductName()));
            entity.setUnitCode(internalUnitCode(firstText(line.unitCode(), line.unitName())));
            entity.setQuantity(amount(firstAmount(line.unitQuantity(), line.baseQuantity())));
            entity.setUnitPrice(amount(firstAmount(line.unitCostPrice(), line.costPrice())));
            entity.setAmount(amount(entity.getQuantity().multiply(entity.getUnitPrice())));
            entity.setRemark(line.remark());
            entity.setCreatedTime(now);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            stockInLineMapper.insert(entity);
        }
    }

    private void replacePurchaseReturnLines(String tenantId, UUID runId, Long purchaseReturnId,
                                            PurchaseReturn item, LocalDateTime now) {
        purchaseReturnLineMapper.delete(Wrappers.<InternalPurchaseReturnOrderLineEntity>lambdaQuery()
                .eq(InternalPurchaseReturnOrderLineEntity::getTenantId, tenantId)
                .eq(InternalPurchaseReturnOrderLineEntity::getPurchaseReturnOrderId, purchaseReturnId));
        int lineNo = 1;
        for (PurchaseReturn.Line line : item.lines()) {
            UpsertResult product = ensureProduct(tenantId, runId, line.sourceGoodsId(),
                    line.goodsCode(), line.goodsName(), now);
            UpsertResult variant = ensureVariant(tenantId, runId, product.id(),
                    firstText(line.optionsId(), line.optionsGoodsCode()), line.optionsGoodsCode(),
                    line.optionsSummary(), now);
            if (product.id() == null || variant.id() == null) continue;
            InternalProductEntity productEntity = productMapper.selectById(product.id());
            InternalProductVariantEntity variantEntity = variantMapper.selectById(variant.id());
            InternalPurchaseReturnOrderLineEntity entity = new InternalPurchaseReturnOrderLineEntity();
            entity.setTenantId(tenantId);
            entity.setPurchaseReturnOrderId(purchaseReturnId);
            entity.setLineNo(lineNo++);
            entity.setProcurementOrderId(procurementOrderId(tenantId, line.purchaseOrderNo()));
            entity.setProcurementNoSnapshot(blank(line.purchaseOrderNo()));
            entity.setProductId(product.id());
            entity.setProductVariantId(variant.id());
            entity.setProductCodeSnapshot(productEntity == null ? null : productEntity.getProductCode());
            entity.setVariantCodeSnapshot(variantEntity == null ? null : variantEntity.getVariantCode());
            entity.setProductNameSnapshot(firstText(line.goodsName(), productEntity == null ? null
                    : productEntity.getProductName()));
            entity.setUnitCode(internalUnitCode(firstText(line.unitCode(), line.unitName())));
            entity.setRequestedQuantity(amount(line.requestedQuantity()));
            entity.setReturnedQuantity(amount(firstAmount(line.confirmedQuantity(), line.requestedQuantity())));
            entity.setUnitPrice(amount(line.returnPrice()));
            entity.setConfirmedUnitPrice(amount(firstAmount(line.confirmedPrice(), line.returnPrice())));
            entity.setLineAmount(lineAmount(line));
            entity.setCostPrice(amount(line.costPrice()));
            entity.setRemark(line.remark());
            entity.setCreatedTime(now);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            purchaseReturnLineMapper.insert(entity);
        }
    }

    private Long procurementOrderId(String tenantId, String sourcePurchaseNo) {
        if (missing(sourcePurchaseNo)) return null;
        MasterSourceBindingEntity binding = bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", "PURCHASE_ORDER")
                .eq("source_code", sourcePurchaseNo.strip())
                .last("LIMIT 1"));
        Long id = longTargetId(binding);
        InternalProcurementOrderEntity entity = id == null ? null : procurementMapper.selectById(id);
        return valid(entity, tenantId) ? id : null;
    }

    private UpsertResult ensureSupplier(String tenantId, UUID runId, String sourceId,
                                        String sourceCode, String sourceName, LocalDateTime now) {
        if (missing(sourceId) && missing(sourceName)) return new UpsertResult(null, ImportResult.oneRejected());
        String effectiveSourceId = !missing(sourceId) ? sourceId : "NAME:" + sourceName.strip();
        return upsertSupplier(tenantId, runId, effectiveSourceId, sourceCode,
                firstText(sourceName, "未知供应商"), null, null, null, null, null,
                null, "PLACEHOLDER:" + effectiveSourceId, "ACTIVE");
    }

    private UpsertResult upsertSupplier(String tenantId, UUID runId, String sourceId,
                                        String sourceCode, String sourceName, String contactName,
                                        String contactPhone, String address, String bankName,
                                        String bankAccount, String remark, String payloadHash,
                                        String statusCode) {
        MasterSourceBindingEntity binding = binding(tenantId, "SUPPLIER", sourceId);
        boolean changed = changed(binding, payloadHash);
        Long id = longTargetId(binding);
        InternalSupplierProfileEntity entity = id == null ? null : supplierMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        if (created) {
            entity = new InternalSupplierProfileEntity();
            entity.setTenantId(tenantId);
            entity.setSupplierCode(uniqueSupplierCode(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || !Objects.equals(entity.getSupplierName(), sourceName)))) {
            entity.setSupplierName(sourceName);
            entity.setContactName(contactName);
            entity.setContactPhone(contactPhone);
            entity.setAddress(address);
            entity.setBankName(bankName);
            entity.setBankAccountNo(bankAccount);
            entity.setStatusCode(statusCode);
            entity.setRemark(remark);
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) supplierMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                supplierMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "SUPPLIER", sourceId, "SUPPLIER_PROFILE",
                entity.getId(), sourceCode, sourceName, statusCode, null, payloadHash, now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult ensureWarehouse(String tenantId, UUID runId, String sourceId,
                                         String sourceCode, String sourceName, LocalDateTime now) {
        if (missing(sourceId) && missing(sourceName)) return new UpsertResult(null, ImportResult.oneRejected());
        String effectiveSourceId = !missing(sourceId) ? sourceId : "NAME:" + sourceName.strip();
        return upsertWarehouse(tenantId, runId, effectiveSourceId, sourceCode,
                firstText(sourceName, "未知仓库"), false, null, null, null,
                null, "PLACEHOLDER:" + effectiveSourceId, "ACTIVE");
    }

    private UpsertResult upsertWarehouse(String tenantId, UUID runId, String sourceId,
                                         String sourceCode, String sourceName, Boolean defaultFlag,
                                         String address, String contactName, String contactPhone,
                                         String remark, String payloadHash, String statusCode) {
        MasterSourceBindingEntity binding = binding(tenantId, "WAREHOUSE", sourceId);
        boolean changed = changed(binding, payloadHash);
        Long id = longTargetId(binding);
        InternalInventoryWarehouseEntity entity = id == null ? null : warehouseMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        if (created) {
            entity = new InternalInventoryWarehouseEntity();
            entity.setTenantId(tenantId);
            entity.setWarehouseCode(uniqueWarehouseCode(tenantId));
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || !Objects.equals(entity.getWarehouseName(), sourceName)))) {
            entity.setWarehouseName(sourceName);
            entity.setRegionCode(null);
            entity.setWarehouseTypeCode(defaultFlag != null && defaultFlag ? "DEFAULT" : "CITY");
            entity.setDefaultFlag(defaultFlag != null && defaultFlag);
            entity.setAddress(address);
            entity.setContactName(contactName);
            entity.setContactPhone(contactPhone);
            entity.setStatusCode(statusCode);
            entity.setRemark(remark);
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            if (created) warehouseMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                warehouseMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, "WAREHOUSE", sourceId, "INVENTORY_WAREHOUSE",
                entity.getId(), sourceCode, sourceName, statusCode, null, payloadHash, now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult ensureProduct(String tenantId, UUID runId, String sourceId,
                                       String sourceCode, String sourceName, LocalDateTime now) {
        if (missing(sourceId) && missing(sourceCode) && missing(sourceName)) {
            return new UpsertResult(null, ImportResult.oneRejected());
        }
        InternalProductEntity mappedByCode = productMappedBySourceCode(tenantId, sourceCode);
        if (mappedByCode != null) {
            return new UpsertResult(mappedByCode.getId(), ImportResult.oneDuplicate());
        }
        String effectiveSourceId = !missing(sourceId) ? sourceId
                : !missing(sourceCode) ? "CODE:" + sourceCode.strip() : "NAME:" + sourceName.strip();
        MasterSourceBindingEntity binding = binding(tenantId, "PRODUCT_SPU", effectiveSourceId);
        Long id = longTargetId(binding);
        InternalProductEntity entity = id == null ? null : productMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        if (created) {
            entity = new InternalProductEntity();
            entity.setTenantId(tenantId);
            entity.setProductCode(uniqueProductCode(tenantId));
            entity.setProductName(firstText(sourceName, firstText(sourceCode, "未知商品")));
            entity.setUnitCode("PIECE");
            entity.setOrderMultipleFlag(false);
            entity.setSaleTypeCode("SPOT");
            entity.setShelfStatusCode("OFF_SHELF");
            entity.setTagCodesJson(json(List.of()));
            entity.setImageKeysJson(json(List.of()));
            entity.setRecommendProductIdsJson(json(List.of()));
            entity.setSubmitStatusCode("DRAFT");
            entity.setRevision(1);
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            productMapper.insert(entity);
        }
        upsertBinding(tenantId, runId, "PRODUCT_SPU", effectiveSourceId, "PRODUCT",
                entity.getId(), sourceCode, entity.getProductName(), null, null,
                "PLACEHOLDER:" + effectiveSourceId, now);
        return new UpsertResult(entity.getId(), created ? ImportResult.oneCreated() : ImportResult.oneDuplicate());
    }

    private UpsertResult ensureVariant(String tenantId, UUID runId, Long productId,
                                       String sourceId, String sourceCode, String snapshot,
                                       LocalDateTime now) {
        if (productId == null) return new UpsertResult(null, ImportResult.oneRejected());
        InternalProductVariantEntity mappedByProduct = variantMappedByProduct(tenantId, productId,
                sourceCode, snapshot);
        if (mappedByProduct != null) {
            return new UpsertResult(mappedByProduct.getId(), ImportResult.oneDuplicate());
        }
        String effectiveSourceId = !missing(sourceId) ? sourceId : "PRODUCT:" + productId + ":DEFAULT";
        MasterSourceBindingEntity binding = binding(tenantId, "PRODUCT_SKU", effectiveSourceId);
        Long id = longTargetId(binding);
        InternalProductVariantEntity entity = id == null ? null : variantMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        if (created) {
            entity = new InternalProductVariantEntity();
            entity.setTenantId(tenantId);
            entity.setProductId(productId);
            entity.setVariantCode(uniqueVariantCode(tenantId));
            entity.setSpecificationSnapshot(snapshot);
            entity.setUnitCode("PIECE");
            entity.setSalePrice(ZERO);
            entity.setMarketPrice(ZERO);
            entity.setPurchasePrice(ZERO);
            entity.setDefaultFlag(variantMapper.selectCount(Wrappers.<InternalProductVariantEntity>lambdaQuery()
                    .eq(InternalProductVariantEntity::getTenantId, tenantId)
                    .eq(InternalProductVariantEntity::getProductId, productId)
                    .eq(InternalProductVariantEntity::getDeleted, 0)) == 0);
            entity.setRevision(1);
            entity.setCreatedBy(SYNC_ACTOR);
            entity.setCreatedTime(now);
            entity.setUpdatedBy(SYNC_ACTOR);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            variantMapper.insert(entity);
        }
        upsertBinding(tenantId, runId, "PRODUCT_SKU", effectiveSourceId, "PRODUCT_VARIANT",
                entity.getId(), sourceCode, snapshot, null, null,
                "PLACEHOLDER:" + effectiveSourceId, now);
        return new UpsertResult(entity.getId(), created ? ImportResult.oneCreated() : ImportResult.oneDuplicate());
    }

    private InternalProductEntity productMappedBySourceCode(String tenantId, String sourceCode) {
        if (missing(sourceCode)) return null;
        List<MasterSourceBindingEntity> values = bindingMapper.selectList(
                Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", "PRODUCT_SPU")
                        .eq("source_code", sourceCode.strip())
                        .eq("source_presence", PRESENT)
                        .notLikeRight("source_object_id", "CODE:")
                        .notLikeRight("source_object_id", "NAME:")
                        .last("LIMIT 2"));
        List<InternalProductEntity> products = values.stream()
                .map(MybatisPlusSupplyDataRepository::longTargetId)
                .filter(Objects::nonNull)
                .map(productMapper::selectById)
                .filter(item -> valid(item, tenantId))
                .toList();
        return products.size() == 1 ? products.getFirst() : null;
    }

    private InternalProductVariantEntity variantMappedByProduct(String tenantId, Long productId,
                                                               String sourceCode, String snapshot) {
        InternalProductVariantEntity bySourceCode = variantMappedBySourceCode(tenantId, productId, sourceCode);
        if (bySourceCode != null) return bySourceCode;
        InternalProductVariantEntity bySnapshot = variantMappedBySnapshot(tenantId, productId, snapshot);
        if (bySnapshot != null) return bySnapshot;
        if (!missing(sourceCode)) {
            List<InternalProductVariantEntity> variants = variantMapper.selectList(
                    Wrappers.<InternalProductVariantEntity>lambdaQuery()
                            .eq(InternalProductVariantEntity::getTenantId, tenantId)
                            .eq(InternalProductVariantEntity::getProductId, productId)
                            .eq(InternalProductVariantEntity::getDeleted, 0)
                            .last("LIMIT 2"));
            if (variants.size() == 1) return variants.getFirst();
        }
        return null;
    }

    private InternalProductVariantEntity variantMappedBySourceCode(String tenantId, Long productId,
                                                                  String sourceCode) {
        if (missing(sourceCode)) return null;
        List<MasterSourceBindingEntity> values = bindingMapper.selectList(
                Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", "PRODUCT_SKU")
                        .eq("source_code", sourceCode.strip())
                        .eq("source_presence", PRESENT)
                        .notLikeRight("source_object_id", "PRODUCT:")
                        .last("LIMIT 2"));
        List<InternalProductVariantEntity> variants = values.stream()
                .map(MybatisPlusSupplyDataRepository::longTargetId)
                .filter(Objects::nonNull)
                .map(variantMapper::selectById)
                .filter(item -> valid(item, tenantId) && Objects.equals(item.getProductId(), productId))
                .toList();
        return variants.size() == 1 ? variants.getFirst() : null;
    }

    private InternalProductVariantEntity variantMappedBySnapshot(String tenantId, Long productId,
                                                                String snapshot) {
        if (missing(snapshot)) return null;
        List<InternalProductVariantEntity> values = variantMapper.selectList(
                Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .eq(InternalProductVariantEntity::getProductId, productId)
                        .eq(InternalProductVariantEntity::getSpecificationSnapshot, snapshot.strip())
                        .eq(InternalProductVariantEntity::getDeleted, 0)
                        .last("LIMIT 2"));
        return values.size() == 1 ? values.getFirst() : null;
    }

    private UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                          SupplyDataObjectType type, int maxPages, String triggerType) {
        LocalDateTime now = now();
        UUID runId = UUID.randomUUID();
        acquireLock(tenantId, type, runId, now);
        MasterDataSyncRunEntity run = new MasterDataSyncRunEntity();
        run.id = text(runId);
        run.tenantId = tenantId;
        run.connectorId = text(connectorId);
        run.sourceSystem = SOURCE_SYSTEM;
        run.objectType = type.name();
        run.triggerType = triggerType;
        run.status = "RUNNING";
        run.maxPages = maxPages;
        run.pageSize = 200;
        run.createdBy = actorId == null ? SYNC_ACTOR : text(actorId);
        run.startedAt = now;
        run.createdAt = now;
        run.updatedAt = now;
        syncRunMapper.insert(run);
        return runId;
    }

    private void acquireLock(String tenantId, SupplyDataObjectType type, UUID runId,
                             LocalDateTime now) {
        MasterDataSyncLockEntity lock = new MasterDataSyncLockEntity();
        lock.id = stableLockId(tenantId, type.name());
        lock.tenantId = tenantId;
        lock.sourceSystem = SOURCE_SYSTEM;
        lock.objectType = type.name();
        lock.runId = text(runId);
        lock.acquiredAt = now;
        lock.expiresAt = now.plus(Duration.ofMinutes(RUN_LEASE_MINUTES));
        int updated = syncLockMapper.update(null, Wrappers.<MasterDataSyncLockEntity>update()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("object_type", type.name())
                .le("expires_at", now)
                .set("run_id", text(runId))
                .set("acquired_at", now)
                .set("expires_at", lock.expiresAt));
        if (updated == 0) {
            try {
                syncLockMapper.insert(lock);
            } catch (RuntimeException error) {
                throw new IllegalStateException("ERP供应链同步正在运行: objectType=" + type.name(), error);
            }
        }
    }

    private void requireRunningRunForUpdate(String tenantId, UUID runId) {
        MasterDataSyncRunEntity run = syncRunMapper.selectOne(
                Wrappers.<MasterDataSyncRunEntity>query()
                        .eq("tenant_id", tenantId).eq("id", text(runId))
                        .eq("status", "RUNNING").last("FOR UPDATE"));
        if (run == null) throw new IllegalStateException("ERP同步run不存在或已终止");
    }

    private void finishRun(String tenantId, UUID runId, String status,
                           RunStatistics statistics, String errorCode, String errorMessage) {
        LocalDateTime now = now();
        syncRunMapper.update(null, Wrappers.<MasterDataSyncRunEntity>update()
                .eq("tenant_id", tenantId).eq("id", text(runId)).eq("status", "RUNNING")
                .set("status", status)
                .set("fetched_count", statistics.fetched())
                .set("created_count", statistics.created())
                .set("changed_count", statistics.changed())
                .set("duplicate_count", statistics.duplicates())
                .set("rejected_count", statistics.rejected())
                .set("unmapped_count", statistics.dictionaryAudit().unmapped())
                .set("dict_snapshot_json", json(statistics.dictionaryAudit().revisions()))
                .set("mapping_issues_json", json(statistics.dictionaryAudit().issues()))
                .set("error_code", errorCode)
                .set("error_message", errorMessage)
                .set("finished_at", now)
                .set("updated_at", now));
        syncLockMapper.delete(Wrappers.<MasterDataSyncLockEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("run_id", text(runId)));
    }

    private MasterSourceBindingEntity binding(String tenantId, String sourceType, String sourceId) {
        if (missing(sourceId)) return null;
        return bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType)
                .eq("source_object_id", sourceId)
                .last("LIMIT 1"));
    }

    private void upsertBinding(String tenantId, UUID runId, String sourceType, String sourceId,
                               String targetType, Long targetId, String sourceCode,
                               String sourceName, String sourceStatus, String sourcePutaway,
                               String payloadHash, LocalDateTime now) {
        MasterSourceBindingEntity entity = binding(tenantId, sourceType, sourceId);
        boolean created = entity == null;
        if (created) {
            entity = new MasterSourceBindingEntity();
            entity.id = UUID.randomUUID().toString();
            entity.tenantId = tenantId;
            entity.sourceSystem = SOURCE_SYSTEM;
            entity.sourceObjectType = sourceType;
            entity.sourceObjectId = sourceId;
            entity.createdAt = now;
            entity.version = 0L;
        } else {
            entity.version = nextVersion(entity.version);
        }
        entity.targetType = targetType;
        entity.targetId = targetId == null ? null : targetId.toString();
        entity.sourceCode = blank(sourceCode);
        entity.sourceName = blank(sourceName);
        entity.sourceStatus = blank(sourceStatus);
        entity.sourcePutaway = blank(sourcePutaway);
        entity.sourcePayloadHash = requiredHash(payloadHash);
        entity.sourcePresence = PRESENT;
        entity.sourceAbsentAt = null;
        entity.lastSyncRunId = text(runId);
        entity.syncedAt = now;
        entity.updatedAt = now;
        if (created) bindingMapper.insert(entity);
        else bindingMapper.updateById(entity);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private static boolean changed(MasterSourceBindingEntity binding, String payloadHash) {
        return binding == null || !Objects.equals(binding.sourcePayloadHash, requiredHash(payloadHash))
                || SOURCE_ABSENT.equals(binding.sourcePresence) || longTargetId(binding) == null;
    }

    private static ImportResult importResult(MasterSourceBindingEntity binding,
                                             boolean created, boolean changed) {
        if (created) return ImportResult.oneCreated();
        return binding == null || changed ? ImportResult.oneChanged() : ImportResult.oneDuplicate();
    }

    private static boolean sourceWritable(String updatedBy) {
        return missing(updatedBy) || SYNC_ACTOR.equals(updatedBy);
    }

    private static boolean valid(InternalSupplierProfileEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalInventoryWarehouseEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProductVariantEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalProcurementOrderEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalStockInOrderEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static boolean valid(InternalPurchaseReturnOrderEntity entity, String tenantId) {
        return entity != null && Objects.equals(tenantId, entity.getTenantId())
                && value(entity.getDeleted(), 0) == 0;
    }

    private static Long longTargetId(MasterSourceBindingEntity binding) {
        if (binding == null || missing(binding.targetId)) return null;
        try {
            return Long.valueOf(binding.targetId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String uniqueSupplierCode(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.SUPPLIER, code -> supplierMapper.selectCount(
                Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                        .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                        .eq(InternalSupplierProfileEntity::getSupplierCode, code)));
    }

    private String uniqueWarehouseCode(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.WAREHOUSE, code -> warehouseMapper.selectCount(
                Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                        .eq(InternalInventoryWarehouseEntity::getWarehouseCode, code)));
    }

    private String uniqueProductCode(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.PRODUCT, code -> productMapper.selectCount(
                Wrappers.<InternalProductEntity>lambdaQuery()
                        .eq(InternalProductEntity::getTenantId, tenantId)
                        .eq(InternalProductEntity::getProductCode, code)));
    }

    private String uniqueVariantCode(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.SKU, code -> variantMapper.selectCount(
                Wrappers.<InternalProductVariantEntity>lambdaQuery()
                        .eq(InternalProductVariantEntity::getTenantId, tenantId)
                        .eq(InternalProductVariantEntity::getVariantCode, code)));
    }

    private String uniqueProcurementNo(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.PURCHASE_ORDER, code -> procurementMapper.selectCount(
                Wrappers.<InternalProcurementOrderEntity>lambdaQuery()
                        .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                        .eq(InternalProcurementOrderEntity::getProcurementNo, code)));
    }

    private String uniqueStockInNo(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.STOCK_IN_ORDER, code -> stockInMapper.selectCount(
                Wrappers.<InternalStockInOrderEntity>lambdaQuery()
                        .eq(InternalStockInOrderEntity::getTenantId, tenantId)
                        .eq(InternalStockInOrderEntity::getStockInNo, code)));
    }

    private String uniquePurchaseReturnNo(String tenantId) {
        return uniqueCode(ErpBusinessCodeRules.PURCHASE_RETURN_ORDER, code -> purchaseReturnMapper.selectCount(
                Wrappers.<InternalPurchaseReturnOrderEntity>lambdaQuery()
                        .eq(InternalPurchaseReturnOrderEntity::getTenantId, tenantId)
                        .eq(InternalPurchaseReturnOrderEntity::getPurchaseReturnNo, code)));
    }

    private String uniqueCode(BusinessCodeRule rule, Function<String, Long> count) {
        return codeGenerator.generateUnique(rule, code -> count.apply(code) == 0);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static BigDecimal firstAmount(BigDecimal first, BigDecimal second) {
        return first == null ? second : first;
    }

    private static BigDecimal totalReturnQuantity(PurchaseReturn item) {
        BigDecimal total = ZERO;
        for (PurchaseReturn.Line line : item.lines()) {
            total = total.add(amount(firstAmount(line.confirmedQuantity(), line.requestedQuantity())));
        }
        return total;
    }

    private static BigDecimal lineAmount(PurchaseReturn.Line line) {
        if (line.amount() != null) return line.amount();
        BigDecimal quantity = amount(firstAmount(line.confirmedQuantity(), line.requestedQuantity()));
        BigDecimal unitPrice = amount(firstAmount(line.confirmedPrice(), line.returnPrice()));
        return quantity.multiply(unitPrice);
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static Long nextVersion(Long value) {
        return value == null ? 1L : value + 1;
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    private static LocalDateTime sourceTime(Instant value, LocalDateTime fallback) {
        return value == null ? fallback : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static String blank(String value) {
        return missing(value) ? null : value.strip();
    }

    private static String firstText(String first, String second) {
        return !missing(first) ? first.strip() : blank(second);
    }

    private static String procurementStatus(String sourceStatus, String sourceName) {
        String value = (firstText(sourceStatus, sourceName) == null ? "" : firstText(sourceStatus, sourceName))
                .toLowerCase(java.util.Locale.ROOT);
        if (value.contains("cancel") || value.contains("取消")) return "CANCELLED";
        if (value.contains("finish") || value.contains("complete") || value.contains("完成")) return "COMPLETED";
        return "SUBMITTED";
    }

    private static String stockStatus(String sourceStatus, String sourceName) {
        String value = (firstText(sourceStatus, sourceName) == null ? "" : firstText(sourceStatus, sourceName))
                .toLowerCase(java.util.Locale.ROOT);
        if (value.contains("cancel") || value.contains("取消")) return "CANCELLED";
        if (value.contains("finish") || value.contains("complete") || value.contains("完成")
                || value.contains("已入库") || value.contains("已出库")) return "CONFIRMED";
        return "DRAFT";
    }

    private static String purchaseReturnStatus(String sourceStatus, String sourceName) {
        String value = (firstText(sourceStatus, sourceName) == null ? "" : firstText(sourceStatus, sourceName))
                .toLowerCase(java.util.Locale.ROOT);
        if (value.contains("cancel") || value.contains("取消")) return "CANCELLED";
        if (value.contains("refund") || value.contains("退款")) return "REFUND_PENDING";
        if (value.contains("finish") || value.contains("complete") || value.contains("完成")) return "COMPLETED";
        if (value.contains("stock") || value.contains("出库")) return "WAIT_STOCK_OUT";
        return "DRAFT";
    }

    private static String warehouseStatus(String sourceStatus) {
        return "F".equalsIgnoreCase(blank(sourceStatus)) ? "DISABLED" : "ACTIVE";
    }

    private static String internalUnitCode(String value) {
        if (missing(value)) return "PIECE";
        return switch (value.strip()) {
            case "箱" -> "BOX";
            case "桶" -> "BUCKET";
            case "份" -> "PORTION";
            case "套" -> "SET";
            case "床" -> "BED";
            default -> "PIECE";
        };
    }

    private static String returnRemark(PurchaseReturn item) {
        String value = firstText(item.reason(), item.remark());
        String supplier = blank(item.supplierName());
        if (supplier == null) return value;
        String prefix = blank(value);
        return prefix == null ? "供应商:" + supplier : prefix + " 供应商:" + supplier;
    }

    private static String optionSnapshot(InventoryBalance item) {
        String first = firstText(item.firstOptionName(), item.firstOptionCode());
        String second = firstText(item.secondOptionName(), item.secondOptionCode());
        if (!missing(first) && !missing(second)) return first + "/" + second;
        return firstText(first, second);
    }

    private static String inventoryVariantSourceId(InventoryBalance item) {
        String source = firstText(item.firstOptionGuid(), item.firstOptionCode());
        String second = firstText(item.secondOptionGuid(), item.secondOptionCode());
        if (!missing(source) && !missing(second)) return source + "::" + second;
        return source;
    }

    private static String inventorySourceId(InventoryBalance item) {
        String goods = firstText(item.goodsGuid(), item.goodsCode());
        String warehouse = firstText(item.warehouseGuid(), item.warehouseCode());
        if (missing(goods) || missing(warehouse)) return null;
        return goods + "|" + warehouse + "|" + firstText(inventoryVariantSourceId(item), "DEFAULT");
    }

    private static String requiredHash(String payloadHash) {
        return missing(payloadHash) ? sha256Hex("EMPTY") : payloadHash.strip();
    }

    private static String stableLockId(String tenantId, String objectType) {
        return UUID.nameUUIDFromBytes((tenantId + "|" + SOURCE_SYSTEM + "|" + objectType)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前JDK不支持SHA-256", error);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception error) {
            throw new IllegalStateException("ERP供应链同步JSON序列化失败", error);
        }
    }

    private record UpsertResult(Long id, ImportResult result) { }
}
