package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.erp.application.port.out.SupplyDataStore;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.erp.domain.enums.ErpStockInType;
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
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;
import com.rigour.shared.core.sync.ExternalSourceCodes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private static final String SOURCE_SYSTEM = ExternalSourceCodes.DOMAIN_DINGHUOBAO;
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String LEGACY_SYNC_ACTOR = "DHB_SYNC";
    private static final String TRIGGER_MANUAL = "MANUAL";
    private static final String TRIGGER_SCHEDULED = "SCHEDULED";
    private static final String PRESENT = "PRESENT";
    private static final String SOURCE_ABSENT = "SOURCE_ABSENT";
    private static final String UNSPECIFIED_PURCHASE_WAREHOUSE_SOURCE_ID =
            "DHB_PURCHASE_TARGET_WAREHOUSE_UNSPECIFIED";
    private static final String UNSPECIFIED_PURCHASE_WAREHOUSE_SOURCE_CODE =
            "DHB_PURCHASE_TARGET_WAREHOUSE_UNSPECIFIED";
    private static final String UNSPECIFIED_PURCHASE_WAREHOUSE_NAME = "订货宝未指定仓库";
    private static final String UNSPECIFIED_PURCHASE_WAREHOUSE_HASH =
            sha256Hex(UNSPECIFIED_PURCHASE_WAREHOUSE_SOURCE_ID);
    private static final long RUN_LEASE_MINUTES = 30;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SOURCE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
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
    public List<String> sourceProductCodes(String tenantId, UUID connectorId) {
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq("connector_id", requiredConnectorId(connectorId))
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
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        return startRun(tenantId, connectorId, actorId, type, maxPages, TRIGGER_MANUAL);
    }

    @Override
    @Transactional
    public UUID startScheduledRun(String tenantId, UUID connectorId, UUID actorId,
                                  SupplyDataObjectType type, int maxPages) {
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        return startRun(tenantId, connectorId, actorId, type, maxPages, TRIGGER_SCHEDULED);
    }

    @Override
    @Transactional
    public ImportResult importSupplier(String tenantId, UUID runId, Supplier item) {
        if (missing(item.sourceId()) || missing(item.name())) return ImportResult.oneRejected();
        UpsertResult result = upsertSupplier(tenantId, runId, item.sourceId(), item.code(),
                item.name(), item.contactName(), firstText(item.mobile(), item.phone()),
                item.address(), item.bankName(), item.bankAccount(), item.remark(),
                item.payloadHash(), "ACTIVE", item.sourceFields());
        return result.result();
    }

    @Override
    @Transactional
    public ImportResult importWarehouse(String tenantId, UUID runId, Warehouse item) {
        if (missing(item.sourceId()) || missing(item.name())) return ImportResult.oneRejected();
        UpsertResult result = upsertWarehouse(tenantId, runId, item.sourceId(), item.code(),
                item.name(), item.defaultFlag(), item.address(), null, item.phone(),
                item.remark(), item.payloadHash(), warehouseStatus(item.sourceStatus()), item.sourceFields());
        return result.result();
    }

    @Override
    @Transactional
    public ImportResult importPurchaseOrder(String tenantId, UUID runId, PurchaseOrder item) {
        if (missing(item.sourceId()) || missing(item.number())) {
            return ImportResult.rejectedIssue("purchaseOrder.identity", purchaseRef(item));
        }
        LocalDateTime now = now();
        String connectorId = runConnectorId(tenantId, runId);
        UpsertResult supplier = resolveSupplier(tenantId, runId, item.supplierSourceId(),
                item.supplierCode(), item.supplierName(), now);
        UpsertResult warehouse = resolvePurchaseWarehouse(tenantId, runId, item,
                item.warehouseCode(), item.warehouseName(), now);
        if (supplier.id() == null) {
            return ImportResult.rejectedIssue("purchaseOrder.supplier",
                    purchaseRef(item) + ", supplierId=" + safe(item.supplierSourceId())
                            + ", supplierCode=" + safe(item.supplierCode())
                            + ", supplierName=" + safe(item.supplierName()));
        }
        if (warehouse.id() == null) {
            return ImportResult.rejectedIssue("purchaseOrder.targetWarehouse",
                    purchaseRef(item) + ", warehouseId=" + safe(item.warehouseSourceId())
                            + ", warehouseCode=" + safe(item.warehouseCode())
                            + ", warehouseName=" + safe(item.warehouseName()));
        }
        if (!procurementLinesResolvable(tenantId, runId, item)) {
            return ImportResult.rejectedIssue("purchaseOrder.lines", purchaseRef(item));
        }
        Instant procurementBusinessTime = item.sourceCreatedAt();
        MasterSourceBindingEntity binding = binding(tenantId, connectorId, "PURCHASE_ORDER", item.sourceId());
        boolean changed = changed(binding, item.payloadHash());
        Long id = longTargetId(binding);
        InternalProcurementOrderEntity entity = id == null ? null : procurementMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        if (created) {
            entity = new InternalProcurementOrderEntity();
            entity.setTenantId(tenantId);
            entity.setProcurementNo(uniqueProcurementNo(tenantId, procurementBusinessTime));
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(sourceTime(item.sourceCreatedAt(), now));
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        boolean procurementNoDateMismatch = !created && sourceWritable(entity.getUpdatedBy())
                && codeDateMismatch(ErpBusinessCodeRules.PURCHASE_ORDER,
                entity.getProcurementNo(), procurementBusinessTime);
        boolean procurementProjectionMismatch = !created && sourceWritable(entity.getUpdatedBy())
                && !Objects.equals(entity.getTotalAmount(), amount(item.totalAmount()));
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || procurementNoDateMismatch
                || procurementProjectionMismatch))) {
            if (procurementNoDateMismatch) {
                entity.setProcurementNo(uniqueProcurementNo(tenantId, procurementBusinessTime));
            }
            entity.setConnectorId(connectorId);
            entity.setSourceSystemCode(SOURCE_SYSTEM);
            entity.setSourceDocumentNo(item.number());
            entity.setSupplierId(supplier.id());
            entity.setTargetWarehouseId(warehouse.id());
            entity.setStatusCode(procurementStatus(item.sourceStatus(), item.sourceStatusName()));
            entity.setExpectedArrivalTime(sourceTime(item.deliveryAt(), null));
            entity.setTotalQuantity(amount(item.goodsCount()));
            entity.setTotalAmount(amount(item.totalAmount()));
            entity.setRemark(firstText(item.remark(), item.internalCommunication()));
            entity.setUpdatedBy(SYSTEM_ACTOR);
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
        return importResult(binding, created,
                changed || procurementNoDateMismatch || procurementProjectionMismatch);
    }

    @Override
    @Transactional
    public ImportResult importWarehousingReceipt(String tenantId, UUID runId,
                                                 WarehousingReceipt item) {
        if (missing(item.sourceId()) || missing(item.number())) return ImportResult.oneRejected();
        LocalDateTime now = now();
        String connectorId = runConnectorId(tenantId, runId);
        String stockInTypeCode = warehousingStockInTypeCode(item);
        Instant stockInBusinessTime = firstInstant(item.storageAt(), item.sourceCreatedAt());
        boolean purchaseStockIn = ErpStockInType.PURCHASE.code().equals(stockInTypeCode);
        WarehousingReceipt.PurchaseLink purchaseLink = firstPurchaseLink(item);
        Long procurementOrderId = purchaseStockIn ? procurementOrderId(tenantId, runId, purchaseLink) : null;
        String procurementNo = purchaseStockIn && purchaseLink != null ? blank(purchaseLink.purchaseOrderNo()) : null;
        UpsertResult warehouse = resolveWarehouse(tenantId, runId, item.warehouseSourceId(),
                null, item.warehouseName(), now);
        UpsertResult supplier = purchaseStockIn
                ? resolveOptionalSupplier(tenantId, runId, item.supplierSourceId(), null, item.supplierName(), now)
                : new UpsertResult(null, ImportResult.oneDuplicate());
        if (warehouse.id() == null || supplier.result().rejected() > 0
                || !stockInLinesResolvable(tenantId, runId, item)) {
            return ImportResult.oneRejected();
        }
        Long supplierId = purchaseStockIn ? supplier.id() : null;
        String transferOrderNo = sourceTransferNo(item);
        MasterSourceBindingEntity binding = binding(tenantId, connectorId, "WAREHOUSING_RECEIPT", item.sourceId());
        boolean changed = changed(binding, item.payloadHash());
        Long id = longTargetId(binding);
        InternalStockInOrderEntity entity = id == null ? null : stockInMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        if (created) {
            entity = new InternalStockInOrderEntity();
            entity.setTenantId(tenantId);
            entity.setStockInNo(uniqueStockInNo(tenantId, stockInBusinessTime));
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(sourceTime(item.sourceCreatedAt(), now));
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        boolean stockInNoDateMismatch = !created && sourceWritable(entity.getUpdatedBy())
                && stockInNoDateMismatch(entity.getStockInNo(), stockInBusinessTime);
        boolean projectionMismatch = !created && sourceWritable(entity.getUpdatedBy())
                && stockInProjectionMismatch(entity, connectorId, item.number(),
                stockInTypeCode, procurementOrderId, procurementNo, transferOrderNo,
                warehouse.id(), supplierId);
        if (created || (sourceWritable(entity.getUpdatedBy())
                && (changed || projectionMismatch || stockInNoDateMismatch))) {
            if (stockInNoDateMismatch) {
                entity.setStockInNo(uniqueStockInNo(tenantId, stockInBusinessTime));
            }
            entity.setConnectorId(connectorId);
            entity.setSourceSystemCode(SOURCE_SYSTEM);
            entity.setSourceDocumentNo(item.number());
            entity.setStockInTypeCode(stockInTypeCode);
            entity.setProcurementOrderId(procurementOrderId);
            entity.setProcurementNo(procurementNo);
            entity.setTransferOrderId(null);
            entity.setTransferOrderNo(transferOrderNo);
            entity.setWarehouseId(warehouse.id());
            entity.setSupplierId(supplierId);
            entity.setStatusCode(stockStatus(item.sourceStatus(), item.sourceStatusName()));
            entity.setStockInTime(sourceTime(item.storageAt(), null));
            entity.setRemark(item.remark());
            entity.setUpdatedBy(SYSTEM_ACTOR);
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
        return importResult(binding, created, changed || projectionMismatch || stockInNoDateMismatch);
    }

    @Override
    @Transactional
    public ImportResult importPurchaseReturn(String tenantId, UUID runId, PurchaseReturn item) {
        if (missing(item.sourceId()) || missing(item.number())) return ImportResult.oneRejected();
        LocalDateTime now = now();
        UpsertResult supplier = resolveSupplier(tenantId, runId, item.supplierSourceId(),
                item.supplierCode(), item.supplierName(), now);
        UpsertResult warehouse = resolveWarehouse(tenantId, runId, item.warehouseSourceId(),
                item.warehouseCode(), item.warehouseName(), now);
        if (supplier.id() == null || warehouse.id() == null
                || !purchaseReturnLinesResolvable(tenantId, runId, item)) {
            return ImportResult.oneRejected();
        }
        Instant returnBusinessTime = firstInstant(item.sendAt(), item.sourceCreatedAt());
        MasterSourceBindingEntity binding = binding(tenantId, runId, "PURCHASE_RETURN", item.sourceId());
        boolean changed = changed(binding, item.payloadHash());
        Long id = longTargetId(binding);
        InternalPurchaseReturnOrderEntity entity = id == null ? null : purchaseReturnMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        if (created) {
            entity = new InternalPurchaseReturnOrderEntity();
            entity.setTenantId(tenantId);
            entity.setPurchaseReturnNo(uniquePurchaseReturnNo(tenantId, returnBusinessTime));
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(sourceTime(item.sourceCreatedAt(), now));
            entity.setDeleted(0);
            entity.setRevision(1);
        }
        boolean purchaseReturnNoDateMismatch = !created && sourceWritable(entity.getUpdatedBy())
                && codeDateMismatch(ErpBusinessCodeRules.PURCHASE_RETURN_ORDER,
                entity.getPurchaseReturnNo(), returnBusinessTime);
        if (created || (sourceWritable(entity.getUpdatedBy()) && (changed || purchaseReturnNoDateMismatch))) {
            if (purchaseReturnNoDateMismatch) {
                entity.setPurchaseReturnNo(uniquePurchaseReturnNo(tenantId, returnBusinessTime));
            }
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
            entity.setUpdatedBy(SYSTEM_ACTOR);
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
        return importResult(binding, created, changed || purchaseReturnNoDateMismatch);
    }

    @Override
    @Transactional
    public ImportResult importInventory(String tenantId, UUID runId, InventoryBalance item) {
        String sourceId = inventorySourceId(item);
        if (missing(sourceId) || missing(item.goodsCode()) || missing(item.warehouseCode())) {
            return ImportResult.oneRejected();
        }
        LocalDateTime now = now();
        UpsertResult product = resolveProduct(tenantId, runId, item.goodsGuid(), item.goodsCode(),
                item.goodsName(), now);
        UpsertResult variant = resolveVariant(tenantId, runId, product.id(), inventoryVariantSourceId(item),
                item.goodsCode(), optionSnapshot(item), now);
        UpsertResult warehouse = resolveWarehouse(tenantId, runId, item.warehouseGuid(), item.warehouseCode(),
                item.warehouseName(), now);
        if (product.id() == null || variant.id() == null || warehouse.id() == null) {
            return ImportResult.oneRejected();
        }
        MasterSourceBindingEntity binding = binding(tenantId, runId, "INVENTORY_BALANCE", sourceId);
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
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(now);
            entity.setUpdatedBy(SYSTEM_ACTOR);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
        }
        if (created || changed || !Objects.equals(entity.getAvailableQuantity(), amount(item.availableQuantity()))) {
            entity.setAvailableQuantity(amount(item.availableQuantity()));
            entity.setLockedQuantity(ZERO);
            entity.setInTransitQuantity(ZERO);
            entity.setUpdatedBy(SYSTEM_ACTOR);
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
    public ImportResult importInventories(String tenantId, UUID runId, List<InventoryBalance> items) {
        if (items == null || items.isEmpty()) {
            return new ImportResult(0, 0, 0, 0, List.of());
        }
        LocalDateTime now = now();
        String connectorId = runConnectorId(tenantId, runId);
        Map<InventoryProductKey, UpsertResult> productCache = new LinkedHashMap<>();
        Map<InventoryVariantKey, UpsertResult> variantCache = new LinkedHashMap<>();
        Map<InventoryWarehouseKey, UpsertResult> warehouseCache = new LinkedHashMap<>();
        List<InventoryProjection> projections = new ArrayList<>();
        long rejected = 0;
        for (InventoryBalance item : items) {
            String sourceId = inventorySourceId(item);
            if (missing(sourceId) || missing(item.goodsCode()) || missing(item.warehouseCode())) {
                rejected++;
                continue;
            }
            UpsertResult product = cachedProduct(tenantId, connectorId, item, productCache);
            UpsertResult variant = cachedVariant(tenantId, connectorId, item, product.id(), variantCache);
            UpsertResult warehouse = cachedWarehouse(tenantId, connectorId, item, warehouseCache);
            if (product.id() == null || variant.id() == null || warehouse.id() == null) {
                rejected++;
                continue;
            }
            projections.add(new InventoryProjection(item, sourceId, product.id(), variant.id(), warehouse.id()));
        }

        Map<String, MasterSourceBindingEntity> bindings = bindingsBySourceId(
                tenantId, connectorId, "INVENTORY_BALANCE",
                projections.stream().map(InventoryProjection::sourceId).toList());
        Map<StockBalanceKey, InternalStockBalanceEntity> balances = stockBalances(tenantId, projections);
        long created = 0;
        long changed = 0;
        long duplicates = 0;
        for (InventoryProjection projection : projections) {
            InventoryBalance item = projection.item();
            MasterSourceBindingEntity binding = bindings.get(projection.sourceId());
            boolean sourceChanged = changed(binding, item.payloadHash());
            StockBalanceKey balanceKey = projection.balanceKey();
            InternalStockBalanceEntity entity = balances.get(balanceKey);
            boolean entityCreated = entity == null;
            if (entityCreated) {
                entity = new InternalStockBalanceEntity();
                entity.setTenantId(tenantId);
                entity.setWarehouseId(projection.warehouseId());
                entity.setProductId(projection.productId());
                entity.setProductVariantId(projection.variantId());
                entity.setRevision(1);
                entity.setCreatedBy(SYSTEM_ACTOR);
                entity.setCreatedTime(now);
                entity.setUpdatedBy(SYSTEM_ACTOR);
                entity.setUpdatedTime(now);
                entity.setDeleted(0);
            }
            BigDecimal availableQuantity = amount(item.availableQuantity());
            boolean quantityChanged = !Objects.equals(entity.getAvailableQuantity(), availableQuantity);
            if (entityCreated || sourceChanged || quantityChanged) {
                entity.setAvailableQuantity(availableQuantity);
                entity.setLockedQuantity(ZERO);
                entity.setInTransitQuantity(ZERO);
                entity.setUpdatedBy(SYSTEM_ACTOR);
                entity.setUpdatedTime(now);
                if (entityCreated) {
                    stockBalanceMapper.insert(entity);
                    balances.put(balanceKey, entity);
                } else {
                    entity.setRevision(value(entity.getRevision(), 1) + 1);
                    stockBalanceMapper.updateById(entity);
                }
            }
            upsertBinding(tenantId, runId, connectorId, binding,
                    "INVENTORY_BALANCE", projection.sourceId(), "STOCK_BALANCE",
                    entity.getId(), item.goodsCode(), item.goodsName(), null, null,
                    item.payloadHash(), now);
            if (entityCreated) created++;
            else if (sourceChanged || quantityChanged) changed++;
            else duplicates++;
        }
        return new ImportResult(created, changed, duplicates, rejected, List.of());
    }

    private UpsertResult cachedProduct(String tenantId, String connectorId, InventoryBalance item,
                                       Map<InventoryProductKey, UpsertResult> cache) {
        InventoryProductKey key = new InventoryProductKey(blank(item.goodsGuid()), blank(item.goodsCode()),
                blank(item.goodsName()));
        return cache.computeIfAbsent(key, ignored -> resolveProduct(tenantId, connectorId,
                item.goodsGuid(), item.goodsCode(), item.goodsName()));
    }

    private UpsertResult cachedVariant(String tenantId, String connectorId, InventoryBalance item,
                                       Long productId, Map<InventoryVariantKey, UpsertResult> cache) {
        InventoryVariantKey key = new InventoryVariantKey(productId, blank(inventoryVariantSourceId(item)),
                blank(item.goodsCode()), blank(optionSnapshot(item)));
        return cache.computeIfAbsent(key, ignored -> resolveVariant(tenantId, connectorId, productId,
                inventoryVariantSourceId(item), item.goodsCode(), optionSnapshot(item)));
    }

    private UpsertResult cachedWarehouse(String tenantId, String connectorId, InventoryBalance item,
                                        Map<InventoryWarehouseKey, UpsertResult> cache) {
        InventoryWarehouseKey key = new InventoryWarehouseKey(blank(item.warehouseGuid()),
                blank(item.warehouseCode()), blank(item.warehouseName()));
        return cache.computeIfAbsent(key, ignored -> resolveWarehouse(tenantId, connectorId,
                item.warehouseGuid(), item.warehouseCode(), item.warehouseName()));
    }

    private Map<String, MasterSourceBindingEntity> bindingsBySourceId(
            String tenantId, String connectorId, String sourceType, Collection<String> sourceIds) {
        List<String> ids = sourceIds == null ? List.of() : sourceIds.stream()
                .filter(value -> !missing(value))
                .map(String::strip)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, MasterSourceBindingEntity> rows = new LinkedHashMap<>();
        for (MasterSourceBindingEntity binding : bindingMapper.selectList(
                Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq("connector_id", connectorId)
                        .eq("source_system", SOURCE_SYSTEM)
                        .eq("source_object_type", sourceType)
                        .in("source_object_id", ids))) {
            rows.putIfAbsent(binding.sourceObjectId, binding);
        }
        return rows;
    }

    private Map<StockBalanceKey, InternalStockBalanceEntity> stockBalances(
            String tenantId, List<InventoryProjection> projections) {
        if (projections == null || projections.isEmpty()) return Map.of();
        Set<Long> warehouseIds = projections.stream().map(InventoryProjection::warehouseId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> productIds = projections.stream().map(InventoryProjection::productId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> variantIds = projections.stream().map(InventoryProjection::variantId)
                .collect(java.util.stream.Collectors.toSet());
        Map<StockBalanceKey, InternalStockBalanceEntity> rows = new LinkedHashMap<>();
        for (InternalStockBalanceEntity balance : stockBalanceMapper.selectList(
                Wrappers.<InternalStockBalanceEntity>lambdaQuery()
                        .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                        .eq(InternalStockBalanceEntity::getDeleted, 0)
                        .in(InternalStockBalanceEntity::getWarehouseId, warehouseIds)
                        .in(InternalStockBalanceEntity::getProductId, productIds)
                        .in(InternalStockBalanceEntity::getProductVariantId, variantIds))) {
            rows.putIfAbsent(new StockBalanceKey(balance.getWarehouseId(),
                    balance.getProductId(), balance.getProductVariantId()), balance);
        }
        return rows;
    }

    @Override
    @Transactional
    public void reconcileSourcePresence(String tenantId, UUID runId,
                                        Map<String, Set<String>> seenSourceIds) {
        LocalDateTime now = now();
        seenSourceIds.forEach((sourceType, values) -> {
            Set<String> seen = values == null ? Set.of() : values;
            String connectorId = runConnectorId(tenantId, runId);
            List<MasterSourceBindingEntity> bindings = bindingMapper.selectList(
                    Wrappers.<MasterSourceBindingEntity>query()
                            .eq("tenant_id", tenantId)
                            .eq("connector_id", connectorId)
                            .eq("source_system", SOURCE_SYSTEM)
                            .eq("source_object_type", sourceType));
            for (MasterSourceBindingEntity binding : bindings) {
                boolean present = seen.contains(binding.sourceObjectId);
                String desired = present ? PRESENT : SOURCE_ABSENT;
                if (Objects.equals(binding.sourcePresence, desired)) continue;
                binding.sourcePresence = desired;
                binding.sourceAbsentAt = present ? null : now;
                binding.lastSyncRunId = text(runId);
                binding.revision = nextVersion(binding.revision);
                binding.updatedBy = SYSTEM_ACTOR;
                binding.updatedTime = now;
                binding.deleted = 0;
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
        finishRun(tenantId, runId, statistics.rejected() == 0 && statistics.dictionaryAudit().unmapped() == 0
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
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision = revision + 1"));
        syncLockMapper.update(null, Wrappers.<MasterDataSyncLockEntity>update()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("run_id", text(runId))
                .set("expires_at", now.plus(Duration.ofMinutes(RUN_LEASE_MINUTES)))
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision = revision + 1"));
    }

    @Override
    public List<ExternalObjectMappingCommand> externalObjectMappings(
            String tenantId, UUID connectorId, UUID runId, SupplyDataObjectType objectType) {
        String connector = requiredConnectorId(connectorId);
        List<ExternalObjectMappingCommand> commands = new ArrayList<>();
        switch (objectType) {
            case SUPPLIER -> appendSupplierMappings(tenantId, connectorId, connector, runId, commands);
            case WAREHOUSE -> appendWarehouseMappings(tenantId, connectorId, connector, runId, commands);
            case PURCHASE_ORDER -> appendProcurementMappings(tenantId, connectorId, connector, runId, commands);
            case PURCHASE_RETURN -> appendPurchaseReturnMappings(tenantId, connectorId, connector, runId, commands);
            case WAREHOUSING_RECEIPT -> appendStockInMappings(tenantId, connectorId, connector, runId, commands);
            case INVENTORY -> appendInventoryBalanceMappings(tenantId, connectorId, connector, runId, commands);
        }
        return List.copyOf(commands);
    }

    private void appendSupplierMappings(String tenantId, UUID connectorId, String connector,
                                        UUID runId, List<ExternalObjectMappingCommand> commands) {
        List<MasterSourceBindingEntity> bindings = sourceBindings(tenantId, connector,
                "SUPPLIER", "SUPPLIER_PROFILE");
        Map<Long, InternalSupplierProfileEntity> suppliers = suppliersByIds(tenantId, targetIds(bindings));
        for (MasterSourceBindingEntity binding : bindings) {
            Long id = longTargetId(binding);
            InternalSupplierProfileEntity supplier = id == null ? null : suppliers.get(id);
            if (!valid(supplier, tenantId)) continue;
            commands.add(externalMapping(connectorId, "SUPPLIER", binding, "SUPPLIER",
                    supplier.getId(), supplier.getSupplierCode(), runId, "ERP供应商同步映射"));
        }
    }

    private void appendWarehouseMappings(String tenantId, UUID connectorId, String connector,
                                         UUID runId, List<ExternalObjectMappingCommand> commands) {
        List<MasterSourceBindingEntity> bindings = sourceBindings(tenantId, connector,
                "WAREHOUSE", "INVENTORY_WAREHOUSE");
        Map<Long, InternalInventoryWarehouseEntity> warehouses = warehousesByIds(tenantId, targetIds(bindings));
        for (MasterSourceBindingEntity binding : bindings) {
            Long id = longTargetId(binding);
            InternalInventoryWarehouseEntity warehouse = id == null ? null : warehouses.get(id);
            if (!valid(warehouse, tenantId)) continue;
            commands.add(externalMapping(connectorId, "WAREHOUSE", binding, "WAREHOUSE",
                    warehouse.getId(), warehouse.getWarehouseCode(), runId, "ERP仓库同步映射"));
        }
    }

    private void appendProcurementMappings(String tenantId, UUID connectorId, String connector,
                                           UUID runId, List<ExternalObjectMappingCommand> commands) {
        List<MasterSourceBindingEntity> bindings = sourceBindings(tenantId, connector,
                "PURCHASE_ORDER", "PROCUREMENT_ORDER");
        Map<Long, InternalProcurementOrderEntity> orders = procurementOrdersByIds(tenantId, targetIds(bindings));
        for (MasterSourceBindingEntity binding : bindings) {
            Long id = longTargetId(binding);
            InternalProcurementOrderEntity order = id == null ? null : orders.get(id);
            if (!valid(order, tenantId)) continue;
            commands.add(externalMapping(connectorId, "PURCHASE_ORDER", binding,
                    "PROCUREMENT_ORDER", order.getId(), order.getProcurementNo(), runId,
                    "ERP采购订单同步映射"));
        }
    }

    private void appendPurchaseReturnMappings(String tenantId, UUID connectorId, String connector,
                                              UUID runId, List<ExternalObjectMappingCommand> commands) {
        List<MasterSourceBindingEntity> bindings = sourceBindings(tenantId, connector,
                "PURCHASE_RETURN", "PURCHASE_RETURN_ORDER");
        Map<Long, InternalPurchaseReturnOrderEntity> orders = purchaseReturnsByIds(tenantId, targetIds(bindings));
        for (MasterSourceBindingEntity binding : bindings) {
            Long id = longTargetId(binding);
            InternalPurchaseReturnOrderEntity order = id == null ? null : orders.get(id);
            if (!valid(order, tenantId)) continue;
            commands.add(externalMapping(connectorId, "PURCHASE_RETURN", binding,
                    "PURCHASE_RETURN_ORDER", order.getId(), order.getPurchaseReturnNo(), runId,
                    "ERP采购退货单同步映射"));
        }
    }

    private void appendStockInMappings(String tenantId, UUID connectorId, String connector,
                                       UUID runId, List<ExternalObjectMappingCommand> commands) {
        List<MasterSourceBindingEntity> bindings = sourceBindings(tenantId, connector,
                "WAREHOUSING_RECEIPT", "STOCK_IN_ORDER");
        Map<Long, InternalStockInOrderEntity> orders = stockInsByIds(tenantId, targetIds(bindings));
        for (MasterSourceBindingEntity binding : bindings) {
            Long id = longTargetId(binding);
            InternalStockInOrderEntity order = id == null ? null : orders.get(id);
            if (!valid(order, tenantId)) continue;
            commands.add(externalMapping(connectorId, "WAREHOUSING_RECEIPT", binding,
                    "STOCK_IN_ORDER", order.getId(), order.getStockInNo(), runId,
                    "ERP入库单同步映射"));
        }
    }

    private void appendInventoryBalanceMappings(String tenantId, UUID connectorId, String connector,
                                                UUID runId, List<ExternalObjectMappingCommand> commands) {
        List<MasterSourceBindingEntity> bindings = sourceBindings(tenantId, connector,
                "INVENTORY_BALANCE", "STOCK_BALANCE");
        Map<Long, InternalStockBalanceEntity> balances = stockBalancesByIds(tenantId, targetIds(bindings));
        for (MasterSourceBindingEntity binding : bindings) {
            Long id = longTargetId(binding);
            InternalStockBalanceEntity balance = id == null ? null : balances.get(id);
            if (!valid(balance, tenantId)) continue;
            commands.add(externalMapping(connectorId, "INVENTORY_BALANCE", binding,
                    "STOCK_BALANCE", balance.getId(), null, runId, "ERP库存余额同步映射"));
        }
    }

    private Set<Long> targetIds(List<MasterSourceBindingEntity> bindings) {
        if (bindings == null || bindings.isEmpty()) return Set.of();
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (MasterSourceBindingEntity binding : bindings) {
            Long id = longTargetId(binding);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private Map<Long, InternalSupplierProfileEntity> suppliersByIds(String tenantId, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, InternalSupplierProfileEntity> rows = new LinkedHashMap<>();
        supplierMapper.selectList(Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                        .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                        .eq(InternalSupplierProfileEntity::getDeleted, 0)
                        .in(InternalSupplierProfileEntity::getId, ids))
                .forEach(item -> rows.put(item.getId(), item));
        return rows;
    }

    private Map<Long, InternalInventoryWarehouseEntity> warehousesByIds(String tenantId, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, InternalInventoryWarehouseEntity> rows = new LinkedHashMap<>();
        warehouseMapper.selectList(Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                        .eq(InternalInventoryWarehouseEntity::getDeleted, 0)
                        .in(InternalInventoryWarehouseEntity::getId, ids))
                .forEach(item -> rows.put(item.getId(), item));
        return rows;
    }

    private Map<Long, InternalProcurementOrderEntity> procurementOrdersByIds(String tenantId, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, InternalProcurementOrderEntity> rows = new LinkedHashMap<>();
        procurementMapper.selectList(Wrappers.<InternalProcurementOrderEntity>lambdaQuery()
                        .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                        .eq(InternalProcurementOrderEntity::getDeleted, 0)
                        .in(InternalProcurementOrderEntity::getId, ids))
                .forEach(item -> rows.put(item.getId(), item));
        return rows;
    }

    private Map<Long, InternalPurchaseReturnOrderEntity> purchaseReturnsByIds(String tenantId, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, InternalPurchaseReturnOrderEntity> rows = new LinkedHashMap<>();
        purchaseReturnMapper.selectList(Wrappers.<InternalPurchaseReturnOrderEntity>lambdaQuery()
                        .eq(InternalPurchaseReturnOrderEntity::getTenantId, tenantId)
                        .eq(InternalPurchaseReturnOrderEntity::getDeleted, 0)
                        .in(InternalPurchaseReturnOrderEntity::getId, ids))
                .forEach(item -> rows.put(item.getId(), item));
        return rows;
    }

    private Map<Long, InternalStockInOrderEntity> stockInsByIds(String tenantId, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, InternalStockInOrderEntity> rows = new LinkedHashMap<>();
        stockInMapper.selectList(Wrappers.<InternalStockInOrderEntity>lambdaQuery()
                        .eq(InternalStockInOrderEntity::getTenantId, tenantId)
                        .eq(InternalStockInOrderEntity::getDeleted, 0)
                        .in(InternalStockInOrderEntity::getId, ids))
                .forEach(item -> rows.put(item.getId(), item));
        return rows;
    }

    private Map<Long, InternalStockBalanceEntity> stockBalancesByIds(String tenantId, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, InternalStockBalanceEntity> rows = new LinkedHashMap<>();
        stockBalanceMapper.selectList(Wrappers.<InternalStockBalanceEntity>lambdaQuery()
                        .eq(InternalStockBalanceEntity::getTenantId, tenantId)
                        .eq(InternalStockBalanceEntity::getDeleted, 0)
                        .in(InternalStockBalanceEntity::getId, ids))
                .forEach(item -> rows.put(item.getId(), item));
        return rows;
    }

    private List<MasterSourceBindingEntity> sourceBindings(String tenantId, String connectorId,
                                                           String sourceType, String targetType) {
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("connector_id", connectorId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType)
                .eq("target_type", targetType)
                .eq("source_presence", PRESENT)
                .eq("deleted", 0));
    }

    private static ExternalObjectMappingCommand externalMapping(
            UUID connectorId, String sourceObjectType, MasterSourceBindingEntity binding,
            String internalObjectType, Long internalObjectId, String internalObjectNo,
            UUID runId, String remark) {
        return new ExternalObjectMappingCommand(connectorId, SOURCE_SYSTEM,
                sourceObjectType, binding.sourceObjectId, firstText(binding.sourceCode, binding.sourceObjectId),
                "ERP", internalObjectType, internalObjectId, internalObjectNo, "ACTIVE",
                runId, instant(binding.syncedAt), null, binding.sourcePayloadHash, null, remark);
    }

    private void replaceProcurementLines(String tenantId, UUID runId, Long orderId,
                                         PurchaseOrder item, LocalDateTime now) {
        procurementLineMapper.delete(Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                .eq(InternalProcurementOrderLineEntity::getProcurementOrderId, orderId));
        int lineNo = 1;
        for (PurchaseOrder.Line line : item.lines()) {
            UpsertResult product = resolveProduct(tenantId, runId, line.sourceGoodsId(),
                    line.goodsCode(), line.goodsName(), now);
            UpsertResult variant = resolveVariant(tenantId, runId, product.id(),
                    variantSourceId(line.sourceGoodsId(), line.optionsId(), line.optionsGoodsCode()),
                    line.optionsGoodsCode(),
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
            entity.setRevision(1);
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(now);
            entity.setUpdatedBy(SYSTEM_ACTOR);
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
            UpsertResult product = resolveProduct(tenantId, runId, line.sourceGoodsId(),
                    line.goodsCode(), line.goodsName(), now);
            UpsertResult variant = resolveVariant(tenantId, runId, product.id(),
                    variantSourceId(line.sourceGoodsId(), line.optionsId(), line.optionsGoodsCode()),
                    line.optionsGoodsCode(),
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
            entity.setRevision(1);
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(now);
            entity.setUpdatedBy(SYSTEM_ACTOR);
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
            UpsertResult product = resolveProduct(tenantId, runId, line.sourceGoodsId(),
                    line.goodsCode(), line.goodsName(), now);
            UpsertResult variant = resolveVariant(tenantId, runId, product.id(),
                    variantSourceId(line.sourceGoodsId(), line.optionsId(), line.optionsGoodsCode()),
                    line.optionsGoodsCode(),
                    line.optionsSummary(), now);
            if (product.id() == null || variant.id() == null) continue;
            InternalProductEntity productEntity = productMapper.selectById(product.id());
            InternalProductVariantEntity variantEntity = variantMapper.selectById(variant.id());
            InternalPurchaseReturnOrderLineEntity entity = new InternalPurchaseReturnOrderLineEntity();
            entity.setTenantId(tenantId);
            entity.setPurchaseReturnOrderId(purchaseReturnId);
            entity.setLineNo(lineNo++);
            entity.setProcurementOrderId(procurementOrderId(tenantId, runId, line.purchaseOrderNo()));
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
            entity.setRevision(1);
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(now);
            entity.setUpdatedBy(SYSTEM_ACTOR);
            entity.setUpdatedTime(now);
            entity.setDeleted(0);
            purchaseReturnLineMapper.insert(entity);
        }
    }

    private Long procurementOrderId(String tenantId, UUID runId, String sourcePurchaseNo) {
        if (missing(sourcePurchaseNo)) return null;
        MasterSourceBindingEntity binding = bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("connector_id", runConnectorId(tenantId, runId))
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", "PURCHASE_ORDER")
                .eq("source_code", sourcePurchaseNo.strip())
                .last("LIMIT 1"));
        Long id = longTargetId(binding);
        InternalProcurementOrderEntity entity = id == null ? null : procurementMapper.selectById(id);
        return valid(entity, tenantId) ? id : null;
    }

    private Long procurementOrderId(String tenantId, UUID runId, WarehousingReceipt.PurchaseLink link) {
        if (link == null) return null;
        if (!missing(link.sourcePurchaseId())) {
            MasterSourceBindingEntity binding = binding(tenantId, runConnectorId(tenantId, runId),
                    "PURCHASE_ORDER", link.sourcePurchaseId().strip());
            Long id = longTargetId(binding);
            InternalProcurementOrderEntity entity = id == null ? null : procurementMapper.selectById(id);
            if (valid(entity, tenantId)) return id;
        }
        return procurementOrderId(tenantId, runId, link.purchaseOrderNo());
    }

    private static WarehousingReceipt.PurchaseLink firstPurchaseLink(WarehousingReceipt item) {
        if (item == null || item.purchaseLinks().isEmpty()) return null;
        return item.purchaseLinks().stream()
                .filter(link -> !missing(link.sourcePurchaseId()) || !missing(link.purchaseOrderNo()))
                .findFirst()
                .orElse(null);
    }

    private static String warehousingStockInTypeCode(WarehousingReceipt item) {
        String typeId = blank(item == null ? null : item.typeId());
        String typeName = blank(item == null ? null : item.typeName());
        String normalized = typeName == null ? "" : typeName.toLowerCase(java.util.Locale.ROOT);
        if ("-1".equals(typeId) || normalized.contains("退货")) return ErpStockInType.RETURN.code();
        if ("8".equals(typeId) || normalized.contains("调拨")) return ErpStockInType.TRANSFER.code();
        return ErpStockInType.PURCHASE.code();
    }

    private static String sourceTransferNo(WarehousingReceipt item) {
        if (item == null) return null;
        if (!ErpStockInType.TRANSFER.code().equals(warehousingStockInTypeCode(item))) return null;
        return sourceField(item.sourceFields(),
                "transfer_num", "transfer_no", "transferNo", "db_num", "dbNo", "db_no");
    }

    private static String sourceField(Map<String, Object> fields, String... keys) {
        if (fields == null || fields.isEmpty()) return null;
        String direct = directSourceField(fields, keys);
        if (direct != null) return direct;
        for (String nestedKey : List.of("info", "data")) {
            Object nested = fields.get(nestedKey);
            if (nested instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new java.util.LinkedHashMap<>();
                map.forEach((name, value) -> normalized.put(String.valueOf(name), value));
                String value = directSourceField(normalized, keys);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static String directSourceField(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            if (!fields.containsKey(key)) continue;
            Object value = fields.get(key);
            String text = value == null ? null : blank(String.valueOf(value));
            if (text != null) return text;
        }
        return null;
    }

    private static boolean stockInProjectionMismatch(InternalStockInOrderEntity entity,
                                                     String connectorId,
                                                     String sourceDocumentNo,
                                                     String stockInTypeCode,
                                                     Long procurementOrderId,
                                                     String procurementNo,
                                                     String transferOrderNo,
                                                     Long warehouseId,
                                                     Long supplierId) {
        return !Objects.equals(entity.getConnectorId(), connectorId)
                || !Objects.equals(entity.getSourceSystemCode(), SOURCE_SYSTEM)
                || !Objects.equals(entity.getSourceDocumentNo(), sourceDocumentNo)
                || !Objects.equals(entity.getStockInTypeCode(), stockInTypeCode)
                || !Objects.equals(entity.getProcurementOrderId(), procurementOrderId)
                || !Objects.equals(entity.getProcurementNo(), procurementNo)
                || !Objects.equals(entity.getTransferOrderNo(), transferOrderNo)
                || !Objects.equals(entity.getWarehouseId(), warehouseId)
                || !Objects.equals(entity.getSupplierId(), supplierId);
    }

    private static boolean stockInNoDateMismatch(String stockInNo, Instant businessTime) {
        return codeDateMismatch(ErpBusinessCodeRules.STOCK_IN_ORDER, stockInNo, businessTime);
    }

    private static boolean codeDateMismatch(BusinessCodeRule rule, String code, Instant businessTime) {
        if (rule == null || missing(code) || businessTime == null) return false;
        String expectedPrefix = rule.prefix()
                + rule.dateFormatter().format(LocalDateTime.ofInstant(businessTime, BUSINESS_ZONE));
        return !code.startsWith(expectedPrefix);
    }

    private boolean procurementLinesResolvable(String tenantId, UUID runId, PurchaseOrder item) {
        for (PurchaseOrder.Line line : item.lines()) {
            if (!lineResolvable(tenantId, runId, line.sourceGoodsId(), line.goodsCode(),
                    line.goodsName(), variantSourceId(line.sourceGoodsId(),
                            line.optionsId(), line.optionsGoodsCode()),
                    line.optionsGoodsCode(), line.optionsSummary())) {
                return false;
            }
        }
        return true;
    }

    private boolean stockInLinesResolvable(String tenantId, UUID runId, WarehousingReceipt item) {
        for (WarehousingReceipt.Line line : item.lines()) {
            if (!lineResolvable(tenantId, runId, line.sourceGoodsId(), line.goodsCode(),
                    line.goodsName(), variantSourceId(line.sourceGoodsId(),
                            line.optionsId(), line.optionsGoodsCode()),
                    line.optionsGoodsCode(), line.optionsSummary())) {
                return false;
            }
        }
        return true;
    }

    private boolean purchaseReturnLinesResolvable(String tenantId, UUID runId, PurchaseReturn item) {
        for (PurchaseReturn.Line line : item.lines()) {
            if (!lineResolvable(tenantId, runId, line.sourceGoodsId(), line.goodsCode(),
                    line.goodsName(), variantSourceId(line.sourceGoodsId(),
                            line.optionsId(), line.optionsGoodsCode()),
                    line.optionsGoodsCode(), line.optionsSummary())) {
                return false;
            }
        }
        return true;
    }

    private boolean lineResolvable(String tenantId, UUID runId, String productSourceId,
                                   String productCode, String productName, String variantSourceId,
                                   String variantCode, String variantSnapshot) {
        UpsertResult product = resolveProduct(tenantId, runId, productSourceId, productCode,
                productName, now());
        if (product.id() == null) return false;
        UpsertResult variant = resolveVariant(tenantId, runId, product.id(), variantSourceId,
                variantCode, variantSnapshot, now());
        return variant.id() != null;
    }

    private UpsertResult resolveOptionalSupplier(String tenantId, UUID runId, String sourceId,
                                                 String sourceCode, String sourceName,
                                                 LocalDateTime now) {
        if (unknownReference(sourceId, sourceCode, sourceName, "未知供应商")) {
            return new UpsertResult(null, ImportResult.oneDuplicate());
        }
        UpsertResult resolved = resolveSupplier(tenantId, runId, sourceId, sourceCode, sourceName, now);
        if (resolved.id() != null || resolved.result().rejected() == 0) return resolved;
        return new UpsertResult(null, ImportResult.oneRejected());
    }

    private UpsertResult resolveSupplier(String tenantId, UUID runId, String sourceId,
                                        String sourceCode, String sourceName, LocalDateTime now) {
        if (missing(sourceId) && missing(sourceCode) && missing(sourceName)) {
            return new UpsertResult(null, ImportResult.oneRejected());
        }
        String connectorId = runConnectorId(tenantId, runId);
        InternalSupplierProfileEntity entity = supplierFromBindingSourceId(tenantId, connectorId, sourceId);
        if (entity == null) entity = supplierFromBindingSourceCode(tenantId, connectorId, sourceCode);
        if (entity == null) entity = supplierFromBindingSourceName(tenantId, connectorId, sourceName);
        return entity == null ? new UpsertResult(null, ImportResult.oneRejected())
                : new UpsertResult(entity.getId(), ImportResult.oneDuplicate());
    }

    private UpsertResult upsertSupplier(String tenantId, UUID runId, String sourceId,
                                        String sourceCode, String sourceName, String contactName,
                                        String contactPhone, String address, String bankName,
                                        String bankAccount, String remark, String payloadHash,
                                        String statusCode, Map<String, Object> sourceFields) {
        MasterSourceBindingEntity binding = binding(tenantId, runId, "SUPPLIER", sourceId);
        boolean changed = changed(binding, payloadHash);
        Long id = longTargetId(binding);
        InternalSupplierProfileEntity entity = id == null ? null : supplierMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        Instant sourceCreatedAt = sourceCreatedAt(sourceFields);
        if (created) {
            entity = new InternalSupplierProfileEntity();
            entity.setTenantId(tenantId);
            entity.setSupplierCode(uniqueSupplierCode(tenantId, sourceCreatedAt));
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(sourceTime(sourceCreatedAt, now));
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
            entity.setUpdatedBy(SYSTEM_ACTOR);
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

    private UpsertResult resolveWarehouse(String tenantId, UUID runId, String sourceId,
                                         String sourceCode, String sourceName, LocalDateTime now) {
        return resolveWarehouse(tenantId, runConnectorId(tenantId, runId), sourceId, sourceCode, sourceName);
    }

    private UpsertResult resolvePurchaseWarehouse(String tenantId, UUID runId, PurchaseOrder item,
                                                  String sourceCode, String sourceName,
                                                  LocalDateTime now) {
        if (unspecifiedPurchaseWarehouse(item)) {
            return upsertWarehouse(tenantId, runId, UNSPECIFIED_PURCHASE_WAREHOUSE_SOURCE_ID,
                    UNSPECIFIED_PURCHASE_WAREHOUSE_SOURCE_CODE, UNSPECIFIED_PURCHASE_WAREHOUSE_NAME,
                    Boolean.FALSE, null, null, null,
                    "订货宝采购单来源未指定目标仓，统一归集为外部占位仓",
                    UNSPECIFIED_PURCHASE_WAREHOUSE_HASH, "DISABLED",
                    unspecifiedPurchaseWarehouseFields(item));
        }
        return resolveWarehouse(tenantId, runId, item.warehouseSourceId(), sourceCode, sourceName, now);
    }

    private UpsertResult resolveWarehouse(String tenantId, String connectorId, String sourceId,
                                         String sourceCode, String sourceName) {
        if (missing(sourceId) && missing(sourceCode) && missing(sourceName)) {
            return new UpsertResult(null, ImportResult.oneRejected());
        }
        InternalInventoryWarehouseEntity entity = warehouseFromBindingSourceId(tenantId, connectorId, sourceId);
        if (entity == null) entity = warehouseFromBindingSourceCode(tenantId, connectorId, sourceCode);
        if (entity == null) entity = warehouseFromBindingSourceName(tenantId, connectorId, sourceName);
        return entity == null ? new UpsertResult(null, ImportResult.oneRejected())
                : new UpsertResult(entity.getId(), ImportResult.oneDuplicate());
    }

    private UpsertResult upsertWarehouse(String tenantId, UUID runId, String sourceId,
                                         String sourceCode, String sourceName, Boolean defaultFlag,
                                         String address, String contactName, String contactPhone,
                                         String remark, String payloadHash, String statusCode,
                                         Map<String, Object> sourceFields) {
        String connectorId = runConnectorId(tenantId, runId);
        MasterSourceBindingEntity binding = binding(tenantId, connectorId, "WAREHOUSE", sourceId);
        boolean changed = changed(binding, payloadHash);
        Long id = longTargetId(binding);
        InternalInventoryWarehouseEntity entity = id == null ? null : warehouseMapper.selectById(id);
        if (!valid(entity, tenantId)) entity = null;
        boolean created = entity == null;
        LocalDateTime now = now();
        Instant sourceCreatedAt = sourceCreatedAt(sourceFields);
        if (created) {
            entity = new InternalInventoryWarehouseEntity();
            entity.setTenantId(tenantId);
            entity.setWarehouseCode(uniqueWarehouseCode(tenantId, sourceCreatedAt));
            entity.setCreatedBy(SYSTEM_ACTOR);
            entity.setCreatedTime(sourceTime(sourceCreatedAt, now));
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
            entity.setUpdatedBy(SYSTEM_ACTOR);
            entity.setUpdatedTime(now);
            if (created) warehouseMapper.insert(entity);
            else {
                entity.setRevision(value(entity.getRevision(), 1) + 1);
                warehouseMapper.updateById(entity);
            }
        }
        upsertBinding(tenantId, runId, connectorId, binding, "WAREHOUSE", sourceId, "INVENTORY_WAREHOUSE",
                entity.getId(), sourceCode, sourceName, statusCode, null, payloadHash, now);
        return new UpsertResult(entity.getId(), importResult(binding, created, changed));
    }

    private UpsertResult resolveProduct(String tenantId, UUID runId, String sourceId,
                                       String sourceCode, String sourceName, LocalDateTime now) {
        return resolveProduct(tenantId, runConnectorId(tenantId, runId), sourceId, sourceCode, sourceName);
    }

    private UpsertResult resolveProduct(String tenantId, String connectorId, String sourceId,
                                       String sourceCode, String sourceName) {
        if (missing(sourceId) && missing(sourceCode) && missing(sourceName)) {
            return new UpsertResult(null, ImportResult.oneRejected());
        }
        InternalProductEntity entity = productFromBindingSourceId(tenantId, connectorId, sourceId);
        if (entity == null) entity = productMappedBySourceCode(tenantId, connectorId, sourceCode);
        if (entity == null) entity = productFromBindingSourceName(tenantId, connectorId, sourceName);
        return entity == null ? new UpsertResult(null, ImportResult.oneRejected())
                : new UpsertResult(entity.getId(), ImportResult.oneDuplicate());
    }

    private UpsertResult resolveVariant(String tenantId, UUID runId, Long productId,
                                       String sourceId, String sourceCode, String snapshot,
                                       LocalDateTime now) {
        return resolveVariant(tenantId, runConnectorId(tenantId, runId), productId, sourceId, sourceCode, snapshot);
    }

    private UpsertResult resolveVariant(String tenantId, String connectorId, Long productId,
                                       String sourceId, String sourceCode, String snapshot) {
        if (productId == null) return new UpsertResult(null, ImportResult.oneRejected());
        InternalProductVariantEntity entity = variantFromBindingSourceId(tenantId, connectorId,
                productId, sourceId);
        if (entity != null) {
            return new UpsertResult(entity.getId(), ImportResult.oneDuplicate());
        }
        InternalProductVariantEntity mappedByProduct = variantMappedByProduct(tenantId, connectorId, productId,
                sourceCode, snapshot);
        if (mappedByProduct != null) {
            return new UpsertResult(mappedByProduct.getId(), ImportResult.oneDuplicate());
        }
        return new UpsertResult(null, ImportResult.oneRejected());
    }

    private InternalSupplierProfileEntity supplierFromBindingSourceId(String tenantId, String connectorId,
                                                                      String sourceId) {
        if (!usableSourceId(sourceId)) return null;
        MasterSourceBindingEntity binding = binding(tenantId, connectorId, "SUPPLIER", sourceId.strip());
        return supplierFromBinding(tenantId, binding);
    }

    private InternalSupplierProfileEntity supplierFromBindingSourceCode(String tenantId, String connectorId,
                                                                        String sourceCode) {
        return supplierFromBindings(tenantId, activeBindingsBySourceField(tenantId, connectorId,
                "SUPPLIER", "SUPPLIER_PROFILE", "source_code", sourceCode));
    }

    private InternalSupplierProfileEntity supplierFromBindingSourceName(String tenantId, String connectorId,
                                                                        String sourceName) {
        return supplierFromBindings(tenantId, activeBindingsBySourceField(tenantId, connectorId,
                "SUPPLIER", "SUPPLIER_PROFILE", "source_name", sourceName));
    }

    private InternalInventoryWarehouseEntity warehouseFromBindingSourceId(String tenantId, String connectorId,
                                                                         String sourceId) {
        if (!usableSourceId(sourceId)) return null;
        MasterSourceBindingEntity binding = binding(tenantId, connectorId, "WAREHOUSE", sourceId.strip());
        return warehouseFromBinding(tenantId, binding);
    }

    private InternalInventoryWarehouseEntity warehouseFromBindingSourceCode(String tenantId, String connectorId,
                                                                           String sourceCode) {
        return warehouseFromBindings(tenantId, activeBindingsBySourceField(tenantId, connectorId,
                "WAREHOUSE", "INVENTORY_WAREHOUSE", "source_code", sourceCode));
    }

    private InternalInventoryWarehouseEntity warehouseFromBindingSourceName(String tenantId, String connectorId,
                                                                           String sourceName) {
        return warehouseFromBindings(tenantId, activeBindingsBySourceField(tenantId, connectorId,
                "WAREHOUSE", "INVENTORY_WAREHOUSE", "source_name", sourceName));
    }

    private InternalProductEntity productFromBindingSourceId(String tenantId, String connectorId,
                                                            String sourceId) {
        if (!usableSourceId(sourceId)) return null;
        MasterSourceBindingEntity binding = binding(tenantId, connectorId, "PRODUCT_SPU", sourceId.strip());
        return productFromBinding(tenantId, binding);
    }

    private InternalProductEntity productFromBindingSourceName(String tenantId, String connectorId,
                                                              String sourceName) {
        return productFromBindings(tenantId, activeBindingsBySourceField(tenantId, connectorId,
                "PRODUCT_SPU", "PRODUCT", "source_name", sourceName));
    }

    private InternalProductVariantEntity variantFromBindingSourceId(String tenantId, String connectorId,
                                                                    Long productId, String sourceId) {
        if (!usableSourceId(sourceId)) return null;
        MasterSourceBindingEntity binding = binding(tenantId, connectorId, "PRODUCT_SKU", sourceId.strip());
        return variantFromBinding(tenantId, productId, binding);
    }

    private List<MasterSourceBindingEntity> activeBindingsBySourceField(String tenantId, String connectorId,
                                                                        String sourceType, String targetType,
                                                                        String column, String value) {
        if (missing(value)) return List.of();
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("connector_id", connectorId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType)
                .eq("target_type", targetType)
                .eq("source_presence", PRESENT)
                .eq("deleted", 0)
                .eq(column, value.strip())
                .last("LIMIT 2"));
    }

    private InternalSupplierProfileEntity supplierFromBindings(String tenantId,
                                                               List<MasterSourceBindingEntity> bindings) {
        java.util.LinkedHashMap<Long, InternalSupplierProfileEntity> targets = new java.util.LinkedHashMap<>();
        for (MasterSourceBindingEntity binding : bindings) {
            InternalSupplierProfileEntity entity = supplierFromBinding(tenantId, binding);
            if (entity != null) targets.put(entity.getId(), entity);
        }
        return targets.size() == 1 ? targets.values().iterator().next() : null;
    }

    private InternalSupplierProfileEntity supplierFromBinding(String tenantId,
                                                             MasterSourceBindingEntity binding) {
        if (!presentTarget(binding, "SUPPLIER_PROFILE")) return null;
        InternalSupplierProfileEntity entity = supplierMapper.selectById(longTargetId(binding));
        return valid(entity, tenantId) ? entity : null;
    }

    private InternalInventoryWarehouseEntity warehouseFromBindings(String tenantId,
                                                                   List<MasterSourceBindingEntity> bindings) {
        java.util.LinkedHashMap<Long, InternalInventoryWarehouseEntity> targets = new java.util.LinkedHashMap<>();
        for (MasterSourceBindingEntity binding : bindings) {
            InternalInventoryWarehouseEntity entity = warehouseFromBinding(tenantId, binding);
            if (entity != null) targets.put(entity.getId(), entity);
        }
        return targets.size() == 1 ? targets.values().iterator().next() : null;
    }

    private InternalInventoryWarehouseEntity warehouseFromBinding(String tenantId,
                                                                 MasterSourceBindingEntity binding) {
        if (!presentTarget(binding, "INVENTORY_WAREHOUSE")) return null;
        InternalInventoryWarehouseEntity entity = warehouseMapper.selectById(longTargetId(binding));
        return valid(entity, tenantId) ? entity : null;
    }

    private InternalProductEntity productFromBindings(String tenantId,
                                                      List<MasterSourceBindingEntity> bindings) {
        java.util.LinkedHashMap<Long, InternalProductEntity> targets = new java.util.LinkedHashMap<>();
        for (MasterSourceBindingEntity binding : bindings) {
            InternalProductEntity entity = productFromBinding(tenantId, binding);
            if (entity != null) targets.put(entity.getId(), entity);
        }
        return targets.size() == 1 ? targets.values().iterator().next() : null;
    }

    private InternalProductEntity productFromBinding(String tenantId,
                                                    MasterSourceBindingEntity binding) {
        if (!presentTarget(binding, "PRODUCT")) return null;
        InternalProductEntity entity = productMapper.selectById(longTargetId(binding));
        return valid(entity, tenantId) ? entity : null;
    }

    private InternalProductVariantEntity variantFromBinding(String tenantId, Long productId,
                                                           MasterSourceBindingEntity binding) {
        if (!presentTarget(binding, "PRODUCT_VARIANT")) return null;
        InternalProductVariantEntity entity = variantMapper.selectById(longTargetId(binding));
        return valid(entity, tenantId) && Objects.equals(entity.getProductId(), productId) ? entity : null;
    }

    private InternalProductEntity productMappedBySourceCode(String tenantId, UUID runId, String sourceCode) {
        return productMappedBySourceCode(tenantId, runConnectorId(tenantId, runId), sourceCode);
    }

    private InternalProductEntity productMappedBySourceCode(String tenantId, String connectorId, String sourceCode) {
        if (missing(sourceCode)) return null;
        List<MasterSourceBindingEntity> values = bindingMapper.selectList(
                Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq("connector_id", connectorId)
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

    private InternalProductVariantEntity variantMappedByProduct(String tenantId, UUID runId, Long productId,
                                                               String sourceCode, String snapshot) {
        return variantMappedByProduct(tenantId, runConnectorId(tenantId, runId), productId, sourceCode, snapshot);
    }

    private InternalProductVariantEntity variantMappedByProduct(String tenantId, String connectorId, Long productId,
                                                               String sourceCode, String snapshot) {
        InternalProductVariantEntity bySourceCode =
                variantMappedBySourceCode(tenantId, connectorId, productId, sourceCode);
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

    private InternalProductVariantEntity variantMappedBySourceCode(String tenantId, UUID runId,
                                                                  Long productId, String sourceCode) {
        return variantMappedBySourceCode(tenantId, runConnectorId(tenantId, runId), productId, sourceCode);
    }

    private InternalProductVariantEntity variantMappedBySourceCode(String tenantId, String connectorId,
                                                                  Long productId, String sourceCode) {
        if (missing(sourceCode)) return null;
        List<MasterSourceBindingEntity> values = bindingMapper.selectList(
                Wrappers.<MasterSourceBindingEntity>query()
                        .eq("tenant_id", tenantId)
                        .eq("connector_id", connectorId)
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
        run.revision = 1;
        run.createdBy = actorId == null ? SYSTEM_ACTOR : text(actorId);
        run.updatedBy = SYSTEM_ACTOR;
        run.startedAt = now;
        run.createdTime = now;
        run.updatedTime = now;
        run.deleted = 0;
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
        lock.revision = 1;
        lock.createdBy = SYSTEM_ACTOR;
        lock.createdTime = now;
        lock.updatedBy = SYSTEM_ACTOR;
        lock.updatedTime = now;
        lock.deleted = 0;
        int updated = syncLockMapper.update(null, Wrappers.<MasterDataSyncLockEntity>update()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("object_type", type.name())
                .le("expires_at", now)
                .set("run_id", text(runId))
                .set("acquired_at", now)
                .set("expires_at", lock.expiresAt)
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision = revision + 1"));
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
                .set("updated_by", SYSTEM_ACTOR)
                .set("updated_time", now)
                .setSql("revision = revision + 1"));
        syncLockMapper.delete(Wrappers.<MasterDataSyncLockEntity>query()
                .eq("tenant_id", tenantId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("run_id", text(runId)));
    }

    private MasterSourceBindingEntity binding(String tenantId, UUID runId, String sourceType, String sourceId) {
        return binding(tenantId, runConnectorId(tenantId, runId), sourceType, sourceId);
    }

    private MasterSourceBindingEntity binding(String tenantId, String connectorId,
                                              String sourceType, String sourceId) {
        if (missing(sourceId)) return null;
        return bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId)
                .eq("connector_id", connectorId)
                .eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType)
                .eq("source_object_id", sourceId)
                .last("LIMIT 1"));
    }

    private void upsertBinding(String tenantId, UUID runId, String sourceType, String sourceId,
                               String targetType, Long targetId, String sourceCode,
                               String sourceName, String sourceStatus, String sourcePutaway,
                               String payloadHash, LocalDateTime now) {
        String connectorId = runConnectorId(tenantId, runId);
        MasterSourceBindingEntity entity = binding(tenantId, connectorId, sourceType, sourceId);
        upsertBinding(tenantId, runId, connectorId, entity, sourceType, sourceId, targetType,
                targetId, sourceCode, sourceName, sourceStatus, sourcePutaway, payloadHash, now);
    }

    private void upsertBinding(String tenantId, UUID runId, String connectorId,
                               MasterSourceBindingEntity entity, String sourceType, String sourceId,
                               String targetType, Long targetId, String sourceCode,
                               String sourceName, String sourceStatus, String sourcePutaway,
                               String payloadHash, LocalDateTime now) {
        boolean created = entity == null;
        if (created) {
            entity = new MasterSourceBindingEntity();
            entity.id = UUID.randomUUID().toString();
            entity.tenantId = tenantId;
            entity.connectorId = connectorId;
            entity.sourceSystem = SOURCE_SYSTEM;
            entity.sourceObjectType = sourceType;
            entity.sourceObjectId = sourceId;
            entity.createdTime = now;
            entity.createdBy = SYSTEM_ACTOR;
            entity.revision = 0L;
        } else {
            entity.revision = nextVersion(entity.revision);
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
        entity.updatedBy = SYSTEM_ACTOR;
        entity.updatedTime = now;
        entity.deleted = 0;
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
        return missing(updatedBy) || SYSTEM_ACTOR.equals(updatedBy) || LEGACY_SYNC_ACTOR.equals(updatedBy);
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

    private static boolean valid(InternalStockBalanceEntity entity, String tenantId) {
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
        return uniqueSupplierCode(tenantId, null);
    }

    private String uniqueSupplierCode(String tenantId, Instant businessTime) {
        return uniqueCode(ErpBusinessCodeRules.SUPPLIER, code -> supplierMapper.selectCount(
                Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                        .eq(InternalSupplierProfileEntity::getTenantId, tenantId)
                        .eq(InternalSupplierProfileEntity::getSupplierCode, code)), businessTime);
    }

    private String uniqueWarehouseCode(String tenantId) {
        return uniqueWarehouseCode(tenantId, null);
    }

    private String uniqueWarehouseCode(String tenantId, Instant businessTime) {
        return uniqueCode(ErpBusinessCodeRules.WAREHOUSE, code -> warehouseMapper.selectCount(
                Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenantId)
                        .eq(InternalInventoryWarehouseEntity::getWarehouseCode, code)), businessTime);
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
        return uniqueProcurementNo(tenantId, null);
    }

    private String uniqueProcurementNo(String tenantId, Instant businessTime) {
        return uniqueCode(ErpBusinessCodeRules.PURCHASE_ORDER, code -> procurementMapper.selectCount(
                Wrappers.<InternalProcurementOrderEntity>lambdaQuery()
                        .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                        .eq(InternalProcurementOrderEntity::getProcurementNo, code)), businessTime);
    }

    private String uniqueStockInNo(String tenantId) {
        return uniqueStockInNo(tenantId, null);
    }

    private String uniqueStockInNo(String tenantId, Instant businessTime) {
        return uniqueCode(ErpBusinessCodeRules.STOCK_IN_ORDER, code -> stockInMapper.selectCount(
                Wrappers.<InternalStockInOrderEntity>lambdaQuery()
                        .eq(InternalStockInOrderEntity::getTenantId, tenantId)
                        .eq(InternalStockInOrderEntity::getStockInNo, code)), businessTime);
    }

    private String uniquePurchaseReturnNo(String tenantId) {
        return uniquePurchaseReturnNo(tenantId, null);
    }

    private String uniquePurchaseReturnNo(String tenantId, Instant businessTime) {
        return uniqueCode(ErpBusinessCodeRules.PURCHASE_RETURN_ORDER, code -> purchaseReturnMapper.selectCount(
                Wrappers.<InternalPurchaseReturnOrderEntity>lambdaQuery()
                        .eq(InternalPurchaseReturnOrderEntity::getTenantId, tenantId)
                        .eq(InternalPurchaseReturnOrderEntity::getPurchaseReturnNo, code)), businessTime);
    }

    private String uniqueCode(BusinessCodeRule rule, Function<String, Long> count) {
        return codeGenerator.generateUnique(rule, code -> count.apply(code) == 0);
    }

    private String uniqueCode(BusinessCodeRule rule, Function<String, Long> count, Instant businessTime) {
        return codeGenerator.generateUnique(rule, businessTime, code -> count.apply(code) == 0);
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

    private String runConnectorId(String tenantId, UUID runId) {
        MasterDataSyncRunEntity run = syncRunMapper.selectOne(Wrappers.<MasterDataSyncRunEntity>query()
                .eq("tenant_id", tenantId)
                .eq("id", text(runId))
                .last("LIMIT 1"));
        if (run == null) throw new IllegalStateException("ERP同步run不存在");
        return requiredConnectorId(run.connectorId);
    }

    private static String requiredConnectorId(UUID connectorId) {
        return requiredConnectorId(text(connectorId));
    }

    private static String requiredConnectorId(String connectorId) {
        if (missing(connectorId)) throw new IllegalStateException("ERP同步connectorId不能为空");
        return connectorId.strip();
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    private static boolean presentTarget(MasterSourceBindingEntity binding, String targetType) {
        return binding != null
                && Objects.equals(binding.targetType, targetType)
                && PRESENT.equals(binding.sourcePresence)
                && value(binding.deleted, 0) == 0
                && longTargetId(binding) != null;
    }

    private static LocalDateTime sourceTime(Instant value, LocalDateTime fallback) {
        return value == null ? fallback : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static Instant firstInstant(Instant first, Instant second) {
        return first == null ? second : first;
    }

    private static Instant sourceCreatedAt(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return null;
        return firstInstant(fields, "create_date", "created_at", "createdAt", "created_time", "CreateDate");
    }

    private static Instant firstInstant(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            Instant value = instant(fields.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        String text = String.valueOf(value);
        if (missing(text)) return null;
        text = text.strip();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // 订货宝常见本地时间格式为 yyyy-MM-dd HH:mm:ss，按业务时区解释。
        }
        try {
            return LocalDateTime.parse(text, SOURCE_DATE_TIME).atZone(BUSINESS_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static boolean usableSourceId(String value) {
        return !missing(value) && !"0".equals(value.strip());
    }

    private static boolean unknownReference(String sourceId, String sourceCode, String sourceName,
                                            String unknownName) {
        if (!missing(sourceCode)) return false;
        if (missing(sourceId) && missing(sourceName)) return true;
        boolean missingOrZeroId = missing(sourceId) || "0".equals(sourceId.strip());
        String name = blank(sourceName);
        return missingOrZeroId && (name == null || Objects.equals(name, unknownName) || name.contains("未知"));
    }

    private static boolean unspecifiedPurchaseWarehouse(PurchaseOrder item) {
        if (item == null) return false;
        boolean missingOrZeroId = missing(item.warehouseSourceId())
                || "0".equals(item.warehouseSourceId().strip());
        if (!missingOrZeroId || !placeholderText(item.warehouseCode())) return false;
        return placeholderText(item.warehouseName());
    }

    private static boolean placeholderText(String value) {
        String text = blank(value);
        return text == null || "-".equals(text);
    }

    private static Map<String, Object> unspecifiedPurchaseWarehouseFields(PurchaseOrder item) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("synthetic", true);
        fields.put("source", "purchaseOrder.targetWarehouse");
        fields.put("sourceWarehouseId", safe(item == null ? null : item.warehouseSourceId()));
        fields.put("sourceWarehouseCode", safe(item == null ? null : item.warehouseCode()));
        fields.put("sourceWarehouseName", safe(item == null ? null : item.warehouseName()));
        if (item != null && item.sourceCreatedAt() != null) {
            fields.put("create_date", item.sourceCreatedAt());
        }
        return fields;
    }

    private static String blank(String value) {
        return missing(value) ? null : value.strip();
    }

    private static String firstText(String first, String second) {
        return !missing(first) ? first.strip() : blank(second);
    }

    private static String purchaseRef(PurchaseOrder item) {
        if (item == null) return "purchaseId=-, purchaseNo=-";
        return "purchaseId=" + safe(item.sourceId()) + ", purchaseNo=" + safe(item.number());
    }

    private static String safe(String value) {
        return missing(value) ? "-" : value.strip();
    }

    private static String variantSourceId(String productSourceId, String optionSourceId,
                                          String optionSourceCode) {
        String option = firstText(optionSourceId, optionSourceCode);
        if (usableSourceId(productSourceId) && !missing(option)) {
            return productSourceId.strip() + "::" + option.strip();
        }
        return option;
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

    private record InventoryProductKey(String sourceId, String sourceCode, String sourceName) { }

    private record InventoryVariantKey(Long productId, String sourceId, String sourceCode, String snapshot) { }

    private record InventoryWarehouseKey(String sourceId, String sourceCode, String sourceName) { }

    private record StockBalanceKey(Long warehouseId, Long productId, Long variantId) { }

    private record InventoryProjection(InventoryBalance item, String sourceId,
                                       Long productId, Long variantId, Long warehouseId) {
        StockBalanceKey balanceKey() {
            return new StockBalanceKey(warehouseId, productId, variantId);
        }
    }

    private record UpsertResult(Long id, ImportResult result) { }
}
