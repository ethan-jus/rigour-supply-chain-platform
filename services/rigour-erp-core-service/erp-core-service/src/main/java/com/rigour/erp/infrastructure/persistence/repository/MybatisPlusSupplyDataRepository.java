package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.erp.api.v1.model.InventoryBalanceView;
import com.rigour.erp.api.v1.model.PurchaseOrderDetailView;
import com.rigour.erp.api.v1.model.PurchaseOrderLineView;
import com.rigour.erp.api.v1.model.PurchaseOrderView;
import com.rigour.erp.api.v1.model.PurchaseReturnDetailView;
import com.rigour.erp.api.v1.model.PurchaseReturnLineView;
import com.rigour.erp.api.v1.model.PurchaseReturnView;
import com.rigour.erp.api.v1.model.PurchaseLinkView;
import com.rigour.erp.api.v1.model.SupplierView;
import com.rigour.erp.api.v1.model.SupplyDataPageView;
import com.rigour.erp.api.v1.model.WarehouseView;
import com.rigour.erp.api.v1.model.WarehousingLineView;
import com.rigour.erp.api.v1.model.WarehousingReceiptDetailView;
import com.rigour.erp.api.v1.model.WarehousingReceiptView;
import com.rigour.erp.application.port.out.SupplyDataStore;
import com.rigour.erp.domain.model.supply.InventoryBalance;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.Supplier;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.erp.domain.model.supply.Warehouse;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import com.rigour.erp.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncRunEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncLockEntity;
import com.rigour.erp.infrastructure.persistence.entity.MasterSourceBindingEntity;
import com.rigour.erp.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.rigour.erp.infrastructure.persistence.entity.PurchaseOrderLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.PurchaseReturnEntity;
import com.rigour.erp.infrastructure.persistence.entity.PurchaseReturnLineEntity;
import com.rigour.erp.infrastructure.persistence.entity.SupplierEntity;
import com.rigour.erp.infrastructure.persistence.entity.WarehouseEntity;
import com.rigour.erp.infrastructure.persistence.entity.WarehousingPurchaseLinkEntity;
import com.rigour.erp.infrastructure.persistence.entity.WarehousingReceiptEntity;
import com.rigour.erp.infrastructure.persistence.entity.WarehousingReceiptLineEntity;
import com.rigour.erp.infrastructure.persistence.mapper.InventoryBalanceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncLockMapper;
import com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper;
import com.rigour.erp.infrastructure.persistence.mapper.PurchaseOrderLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.PurchaseOrderMapper;
import com.rigour.erp.infrastructure.persistence.mapper.PurchaseReturnLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.PurchaseReturnMapper;
import com.rigour.erp.infrastructure.persistence.mapper.SupplierMapper;
import com.rigour.erp.infrastructure.persistence.mapper.WarehouseMapper;
import com.rigour.erp.infrastructure.persistence.mapper.WarehousingPurchaseLinkMapper;
import com.rigour.erp.infrastructure.persistence.mapper.WarehousingReceiptLineMapper;
import com.rigour.erp.infrastructure.persistence.mapper.WarehousingReceiptMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * ERP 供应链唯一 MyBatis-Plus 仓储实现。
 *
 * <p>所有查写均绑定 tenantId；来源绑定负责根对象幂等，单据明细按来源行 ID 替换。</p>
 */
@Repository
public class MybatisPlusSupplyDataRepository implements SupplyDataStore {
    private static final String SOURCE_SYSTEM = "DINGHUOBAO";
    private static final String EXTERNAL_PRIMARY = "EXTERNAL_PRIMARY";
    private static final ObjectMapper SOURCE_FIELDS_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private final SupplierMapper supplierMapper;
    private final WarehouseMapper warehouseMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final PurchaseOrderLineMapper purchaseOrderLineMapper;
    private final PurchaseReturnMapper purchaseReturnMapper;
    private final PurchaseReturnLineMapper purchaseReturnLineMapper;
    private final WarehousingReceiptMapper warehousingReceiptMapper;
    private final WarehousingReceiptLineMapper warehousingReceiptLineMapper;
    private final WarehousingPurchaseLinkMapper warehousingPurchaseLinkMapper;
    private final InventoryBalanceMapper inventoryBalanceMapper;
    private final MasterSourceBindingMapper bindingMapper;
    private final MasterDataSyncRunMapper syncRunMapper;
    private final MasterDataSyncLockMapper syncLockMapper;
    private final Clock clock;

    public MybatisPlusSupplyDataRepository(
            SupplierMapper supplierMapper, WarehouseMapper warehouseMapper,
            PurchaseOrderMapper purchaseOrderMapper, PurchaseOrderLineMapper purchaseOrderLineMapper,
            PurchaseReturnMapper purchaseReturnMapper, PurchaseReturnLineMapper purchaseReturnLineMapper,
            WarehousingReceiptMapper warehousingReceiptMapper,
            WarehousingReceiptLineMapper warehousingReceiptLineMapper,
            WarehousingPurchaseLinkMapper warehousingPurchaseLinkMapper,
            InventoryBalanceMapper inventoryBalanceMapper, MasterSourceBindingMapper bindingMapper,
            MasterDataSyncRunMapper syncRunMapper, MasterDataSyncLockMapper syncLockMapper, Clock clock) {
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.purchaseOrderLineMapper = purchaseOrderLineMapper;
        this.purchaseReturnMapper = purchaseReturnMapper;
        this.purchaseReturnLineMapper = purchaseReturnLineMapper;
        this.warehousingReceiptMapper = warehousingReceiptMapper;
        this.warehousingReceiptLineMapper = warehousingReceiptLineMapper;
        this.warehousingPurchaseLinkMapper = warehousingPurchaseLinkMapper;
        this.inventoryBalanceMapper = inventoryBalanceMapper;
        this.bindingMapper = bindingMapper;
        this.syncRunMapper = syncRunMapper;
        this.syncLockMapper = syncLockMapper;
        this.clock = clock;
    }

    @Override
    public SupplyDataPageView<SupplierView> suppliers(String tenantId, int begin, int step,
                                             String query, String status) {
        var wrapper = Wrappers.<SupplierEntity>query().eq("tenant_id", tenantId);
        applySearch(wrapper, query, "supplier_code", "name", "contact_name");
        applyStatus(wrapper, status, "internal_status");
        long total = supplierMapper.selectCount(wrapper);
        List<SupplierEntity> page = supplierMapper.selectList(wrapper.orderByDesc("updated_at")
                .orderByAsc("id").last(limitSql(begin, step)));
        return new SupplyDataPageView<>(total, begin, step, page.stream().map(item ->
                new SupplierView(item.id, item.sourceSupplierId, item.sourceSupplierGuid,
                        item.supplierCode, item.name, item.areaName, item.address, item.contactName, item.mobile,
                        item.phone, item.email, item.accountName, item.bankName, item.bankAccount, item.invoiceTitle,
                        item.taxpayerNumber, item.remark, instant(item.sourceUpdatedAt),
                        instant(item.sourceSyncedAt))).toList());
    }

    @Override
    public SupplyDataPageView<WarehouseView> warehouses(String tenantId, int begin, int step,
                                               String query, String status) {
        var wrapper = Wrappers.<WarehouseEntity>query().eq("tenant_id", tenantId);
        applySearch(wrapper, query, "warehouse_code", "name", "address");
        applyStatus(wrapper, status, "internal_status");
        long total = warehouseMapper.selectCount(wrapper);
        List<WarehouseEntity> page = warehouseMapper.selectList(wrapper
                .orderByAsc("CAST(warehouse_code AS UNSIGNED)")
                .orderByAsc("warehouse_code").orderByAsc("id").last(limitSql(begin, step)));
        return new SupplyDataPageView<>(total, begin, step, page.stream().map(item ->
                new WarehouseView(item.id, item.sourceWarehouseId, item.sourceWarehouseGuid,
                        item.warehouseCode, item.name, warehouseStatus(item.sourceStatus), item.sourceStatus,
                        Boolean.TRUE.equals(item.sourceDefaultFlag), item.acreage, item.phone,
                        item.address, item.collaboratorSourceId, item.remark, item.internalStatus,
                        item.ownershipState, instant(item.sourceSyncedAt))).toList());
    }

    @Override
    public SupplyDataPageView<PurchaseOrderView> purchaseOrders(String tenantId, int begin, int step,
                                                       String query, String status) {
        var wrapper = Wrappers.<PurchaseOrderEntity>query().eq("tenant_id", tenantId);
        applySearch(wrapper, query, "purchase_order_no", "supplier_code_snapshot",
                "supplier_name_snapshot", "warehouse_name_snapshot");
        applyStatus(wrapper, status, "internal_status", "source_status");
        long total = purchaseOrderMapper.selectCount(wrapper);
        List<PurchaseOrderEntity> page = purchaseOrderMapper.selectList(wrapper.orderByDesc("updated_at")
                .orderByAsc("id").last(limitSql(begin, step)));
        Map<String, Integer> lineCounts = lineCounts(tenantId, ids(page, item -> item.id), "PURCHASE");
        return new SupplyDataPageView<>(total, begin, step, page.stream()
                .map(item -> purchaseOrderView(item, lineCounts.getOrDefault(item.id, 0))).toList());
    }

    @Override
    public PurchaseOrderDetailView purchaseOrder(String tenantId, String id) {
        PurchaseOrderEntity entity = purchaseOrderMapper.selectOne(Wrappers.<PurchaseOrderEntity>query()
                .eq("tenant_id", tenantId).eq("id", id).last("LIMIT 1"));
        if (entity == null) throw notFound("采购单不存在");
        List<PurchaseOrderLineEntity> lines = purchaseOrderLineMapper.selectList(
                Wrappers.<PurchaseOrderLineEntity>query().eq("tenant_id", tenantId)
                        .eq("purchase_order_id", id).orderByAsc("created_at").orderByAsc("id"));
        return new PurchaseOrderDetailView(entity.id, entity.sourcePurchaseId, entity.purchaseOrderNo,
                prefer(entity.sourceSupplierId, sourceSupplierId(tenantId, entity.supplierId)),
                entity.supplierCodeSnapshot, entity.supplierNameSnapshot,
                prefer(entity.sourceWarehouseId, sourceWarehouseId(tenantId, entity.warehouseId)),
                entity.warehouseCodeSnapshot, entity.warehouseNameSnapshot, entity.staffSourceId,
                entity.staffName, entity.sourceStatus, entity.sourceStatusName,
                entity.sourcePaymentStatus, entity.sourcePaymentName, instant(entity.deliveryAt),
                instant(entity.sourceCreatedAt), instant(entity.sourceUpdatedAt), entity.totalAmount,
                entity.paidAmount, entity.goodsCount, entity.sourceDownloaded, entity.remark,
                entity.internalCommunication, parseSourceFields(entity.attributesJson),
                lines.stream().map(MybatisPlusSupplyDataRepository::purchaseOrderLine).toList());
    }

    @Override
    public SupplyDataPageView<PurchaseReturnView> purchaseReturns(String tenantId, int begin, int step,
                                                         String query, String status) {
        var wrapper = Wrappers.<PurchaseReturnEntity>query().eq("tenant_id", tenantId);
        applySearch(wrapper, query, "purchase_return_no", "supplier_code_snapshot",
                "supplier_name_snapshot", "return_reason");
        applyStatus(wrapper, status, "internal_status", "source_status");
        long total = purchaseReturnMapper.selectCount(wrapper);
        List<PurchaseReturnEntity> page = purchaseReturnMapper.selectList(wrapper.orderByDesc("updated_at")
                .orderByAsc("id").last(limitSql(begin, step)));
        Map<String, Integer> lineCounts = lineCounts(tenantId, ids(page, item -> item.id), "RETURN");
        return new SupplyDataPageView<>(total, begin, step, page.stream()
                .map(item -> purchaseReturnView(item, lineCounts.getOrDefault(item.id, 0))).toList());
    }

    @Override
    public PurchaseReturnDetailView purchaseReturn(String tenantId, String id) {
        PurchaseReturnEntity entity = purchaseReturnMapper.selectOne(Wrappers.<PurchaseReturnEntity>query()
                .eq("tenant_id", tenantId).eq("id", id).last("LIMIT 1"));
        if (entity == null) throw notFound("采购退货单不存在");
        List<PurchaseReturnLineEntity> lines = purchaseReturnLineMapper.selectList(
                Wrappers.<PurchaseReturnLineEntity>query().eq("tenant_id", tenantId)
                        .eq("purchase_return_id", id).orderByAsc("created_at").orderByAsc("id"));
        return new PurchaseReturnDetailView(entity.id, entity.sourceReturnId, entity.purchaseReturnNo,
                prefer(entity.sourceSupplierId, sourceSupplierId(tenantId, entity.supplierId)),
                entity.supplierCodeSnapshot, entity.supplierNameSnapshot,
                prefer(entity.sourceWarehouseId, sourceWarehouseId(tenantId, entity.warehouseId)),
                entity.warehouseCodeSnapshot, entity.warehouseNameSnapshot, entity.staffSourceId,
                entity.staffName, entity.sourceStatus, entity.sourceStatusName, entity.returnAmount,
                entity.discountAmount, entity.returnReason, instant(entity.sourceCreatedAt),
                instant(entity.returnSendAt), entity.internalCommunication, entity.remark,
                entity.detailCount, entity.contactName, entity.contactPhone,
                entity.contactAddress, parseJsonList(entity.cityIdsJson),
                parseJsonList(entity.cityNamesJson), entity.sourceDevice, entity.parentReturnSourceId,
                entity.parentCompanySourceId, entity.sourceDownloaded,
                parseSourceFields(entity.attributesJson),
                lines.stream().map(MybatisPlusSupplyDataRepository::purchaseReturnLine).toList());
    }

    @Override
    public SupplyDataPageView<WarehousingReceiptView> warehousingReceipts(String tenantId, int begin, int step,
                                                                 String query, String status) {
        var wrapper = Wrappers.<WarehousingReceiptEntity>query().eq("tenant_id", tenantId);
        applySearch(wrapper, query, "warehousing_no", "warehouse_name_snapshot",
                "supplier_name_snapshot", "source_type_name");
        applyStatus(wrapper, status, "internal_status", "source_status");
        long total = warehousingReceiptMapper.selectCount(wrapper);
        List<WarehousingReceiptEntity> page = warehousingReceiptMapper.selectList(wrapper.orderByDesc("updated_at")
                .orderByAsc("id").last(limitSql(begin, step)));
        Map<String, Integer> lineCounts = lineCounts(tenantId, ids(page, item -> item.id), "WAREHOUSING");
        return new SupplyDataPageView<>(total, begin, step, page.stream().map(item ->
                new WarehousingReceiptView(item.id, item.sourceWarehousingId, item.warehousingNo,
                        item.sourceWarehouseId, item.warehouseNameSnapshot, item.sourceSupplierId,
                        item.supplierNameSnapshot, item.sourceTypeId, item.sourceTypeName,
                        item.sourceStatus, item.sourceStatusName, item.internalStatus, item.staffName,
                        item.clientSourceId, item.accountSourceId, item.collaboratorSourceId,
                        item.collaboratorName, item.logisticsSourceId, item.expressNumber,
                        item.totalAmount, item.costAmount, item.freightAmount,
                        instant(item.storageAt), instant(item.sourceCreatedAt), instant(item.sourceUpdatedAt),
                        item.remark, item.sourceApiFlag, item.splitType,
                        lineCounts.getOrDefault(item.id, 0), instant(item.sourceSyncedAt))).toList());
    }

    @Override
    public WarehousingReceiptDetailView warehousingReceipt(String tenantId, String id) {
        WarehousingReceiptEntity entity = warehousingReceiptMapper.selectOne(
                Wrappers.<WarehousingReceiptEntity>query().eq("tenant_id", tenantId)
                        .eq("id", id).last("LIMIT 1"));
        if (entity == null) throw notFound("入库单不存在");
        List<WarehousingReceiptLineEntity> lines = warehousingReceiptLineMapper.selectList(
                Wrappers.<WarehousingReceiptLineEntity>query().eq("tenant_id", tenantId)
                        .eq("warehousing_receipt_id", id).orderByAsc("created_at").orderByAsc("id"));
        List<WarehousingPurchaseLinkEntity> links = warehousingPurchaseLinkMapper.selectList(
                Wrappers.<WarehousingPurchaseLinkEntity>query().eq("tenant_id", tenantId)
                        .eq("warehousing_receipt_id", id).orderByAsc("created_at").orderByAsc("id"));
        return new WarehousingReceiptDetailView(entity.id, entity.sourceWarehousingId, entity.warehousingNo,
                entity.sourceWarehouseId, entity.warehouseNameSnapshot, entity.sourceSupplierId,
                entity.supplierNameSnapshot, entity.sourceTypeId, entity.sourceTypeName, entity.sourceStatus,
                entity.sourceStatusName, entity.staffName, entity.clientSourceId, entity.accountSourceId,
                entity.collaboratorSourceId, entity.collaboratorName, entity.logisticsSourceId,
                entity.expressNumber, instant(entity.storageAt), instant(entity.sourceCreatedAt),
                instant(entity.sourceUpdatedAt), entity.freightAmount, entity.totalAmount, entity.costAmount,
                entity.sourceApiFlag, entity.splitType, entity.remark,
                parseSourceFields(entity.attributesJson),
                lines.stream().map(MybatisPlusSupplyDataRepository::warehousingLine).toList(),
                links.stream().map(item -> new PurchaseLinkView(item.sourcePurchaseId, item.purchaseOrderNo)).toList());
    }

    @Override
    public SupplyDataPageView<InventoryBalanceView> inventory(String tenantId, int begin, int step,
                                                     String query, String warehouseCode) {
        var wrapper = Wrappers.<InventoryBalanceEntity>query().eq("tenant_id", tenantId);
        applySearch(wrapper, query, "source_goods_code", "source_goods_name",
                "first_option_name", "second_option_name");
        if (!missing(warehouseCode)) wrapper.eq("source_warehouse_code", warehouseCode.strip());
        long total = inventoryBalanceMapper.selectCount(wrapper);
        List<InventoryBalanceEntity> page = inventoryBalanceMapper.selectList(wrapper
                .orderByAsc("CAST(source_warehouse_code AS UNSIGNED)")
                .orderByAsc("source_warehouse_code").orderByAsc("id").last(limitSql(begin, step)));
        return new SupplyDataPageView<>(total, begin, step, page.stream().map(item ->
                new InventoryBalanceView(item.id, item.sourceGoodsGuid, item.sourceWarehouseCode,
                        item.sourceWarehouseName, item.sourceWarehouseGuid, item.sourceGoodsCode,
                        item.sourceGoodsName, item.firstOptionGuid, item.firstOptionCode,
                        item.firstOptionName, item.secondOptionGuid, item.secondOptionCode,
                        item.secondOptionName, options(item), item.realQuantity, item.availableQuantity,
                        item.reservedQuantity, item.inTransitQuantity, item.calculationOrigin,
                        instant(item.sourceSyncedAt))).toList());
    }

    @Override
    public List<String> sourceProductCodes(String tenantId) {
        return bindingMapper.selectList(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", "PRODUCT_SPU").eq("source_presence", "PRESENT")
                .isNotNull("source_code"))
                .stream().map(item -> item.sourceCode).filter(value -> !missing(value)).distinct().toList();
    }

    @Override
    @Transactional
    public UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                         SupplyDataObjectType type, int maxPages) {
        return startRun(tenantId, connectorId, actorId, type, maxPages, "MANUAL");
    }

    @Override
    @Transactional
    public UUID startScheduledRun(String tenantId, UUID connectorId, UUID actorId,
                                  SupplyDataObjectType type, int maxPages) {
        return startRun(tenantId, connectorId, actorId, type, maxPages, "SCHEDULED");
    }

    private UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                          SupplyDataObjectType type, int maxPages, String triggerType) {
        UUID runId = UUID.randomUUID();
        LocalDateTime now = now();
        acquireRunLock(tenantId, type.name(), runId, now);
        MasterDataSyncRunEntity entity = new MasterDataSyncRunEntity();
        entity.id = runId.toString();
        entity.tenantId = tenantId;
        entity.connectorId = connectorId.toString();
        entity.sourceSystem = SOURCE_SYSTEM;
        entity.objectType = type.name();
        entity.triggerType = triggerType;
        entity.status = "RUNNING";
        entity.maxPages = maxPages;
        entity.pageSize = 200;
        entity.startedAt = now;
        entity.createdBy = actorId == null ? null : actorId.toString();
        entity.createdAt = now;
        entity.updatedAt = now;
        syncRunMapper.insert(entity);
        return runId;
    }

    @Transactional
    @Override
    public ImportResult importSupplier(String tenantId, UUID runId, Supplier item) {
        if (missing(item.sourceId()) || missing(item.name())) return ImportResult.oneRejected();
        Binding binding = binding(tenantId, "SUPPLIER", item.sourceId(), "SUPPLIER", item.payloadHash());
        SupplierEntity entity = binding.existing() ? supplierMapper.selectById(binding.targetId()) : null;
        boolean created = entity == null;
        if (entity == null) entity = new SupplierEntity();
        String sourceFieldsJson = sourceFieldsJson(item.sourceFields());
        boolean sourceFieldsNeedRepair = !created && !Objects.equals(entity.attributesJson, sourceFieldsJson);
        LocalDateTime now = now();
        if (created) initializeSupplier(entity, tenantId, binding.targetId(), item, now);
        if (created || binding.changed() || sourceFieldsNeedRepair) {
            entity.sourceSupplierGuid = item.sourceGuid();
            entity.name = item.name();
            entity.areaName = item.areaName();
            entity.address = item.address();
            entity.contactName = item.contactName();
            entity.mobile = item.mobile();
            entity.phone = item.phone();
            entity.email = item.email();
            entity.accountName = item.accountName();
            entity.bankName = item.bankName();
            entity.bankAccount = item.bankAccount();
            entity.invoiceTitle = item.invoiceTitle();
            entity.taxpayerNumber = item.taxpayerNumber();
            entity.remark = item.remark();
            entity.sourceUpdatedAt = local(item.sourceUpdatedAt());
            entity.attributesJson = sourceFieldsJson;
            entity.sourceSyncedAt = now;
            entity.updatedAt = now;
            if (!created && binding.changed()) entity.version = next(entity.version);
            if (created) supplierMapper.insert(entity); else supplierMapper.updateById(entity);
        }
        saveBinding(tenantId, runId, "SUPPLIER", item.sourceId(), "SUPPLIER", entity.id,
                item.code(), item.name(), null, item.sourceUpdatedAt(), item.payloadHash(), now, binding.entity());
        return outcome(created, binding.changed() || sourceFieldsNeedRepair);
    }

    @Transactional
    @Override
    public ImportResult importWarehouse(String tenantId, UUID runId, Warehouse item) {
        if (missing(item.sourceId()) || missing(item.name())) return ImportResult.oneRejected();
        Binding binding = binding(tenantId, "WAREHOUSE", item.sourceId(), "WAREHOUSE", item.payloadHash());
        WarehouseEntity entity = binding.existing() ? warehouseMapper.selectById(binding.targetId()) : null;
        boolean created = entity == null;
        if (entity == null) entity = new WarehouseEntity();
        String sourceFieldsJson = sourceFieldsJson(item.sourceFields());
        boolean sourceFieldsNeedRepair = !created && !Objects.equals(entity.attributesJson, sourceFieldsJson);
        LocalDateTime now = now();
        if (created) {
            entity.id = binding.targetId(); entity.tenantId = tenantId;
            entity.warehouseCode = uniqueWarehouseCode(tenantId, item.code(), item.sourceId());
            entity.sourceWarehouseId = item.sourceId(); entity.internalStatus = "ACTIVE";
            entity.ownershipState = EXTERNAL_PRIMARY; entity.recordOrigin = "IMPORTED";
            entity.version = 0L; entity.createdAt = now;
        }
        if (created || binding.changed() || sourceFieldsNeedRepair) {
            entity.name = item.name(); entity.sourceWarehouseGuid = item.sourceGuid();
            entity.sourceStatus = item.sourceStatus(); entity.sourceDefaultFlag = item.defaultFlag();
            entity.acreage = item.acreage(); entity.phone = item.phone();
            entity.address = item.address(); entity.collaboratorSourceId = item.collaboratorSourceId();
            entity.remark = item.remark(); entity.attributesJson = sourceFieldsJson;
            entity.sourceSyncedAt = now; entity.updatedAt = now;
            if (!created && binding.changed()) entity.version = next(entity.version);
            if (created) warehouseMapper.insert(entity); else warehouseMapper.updateById(entity);
        }
        saveBinding(tenantId, runId, "WAREHOUSE", item.sourceId(), "WAREHOUSE", entity.id,
                item.code(), item.name(), item.sourceStatus(), null, item.payloadHash(), now, binding.entity());
        return outcome(created, binding.changed() || sourceFieldsNeedRepair);
    }

    @Transactional
    @Override
    public ImportResult importPurchaseOrder(String tenantId, UUID runId, PurchaseOrder item) {
        if (missing(item.sourceId()) || missing(item.number())) return ImportResult.oneRejected();
        Binding binding = binding(tenantId, "PURCHASE_ORDER", item.sourceId(), "PURCHASE_ORDER", item.payloadHash());
        PurchaseOrderEntity entity = binding.existing() ? purchaseOrderMapper.selectById(binding.targetId()) : null;
        boolean created = entity == null;
        if (entity == null) entity = new PurchaseOrderEntity();
        String sourceFieldsJson = sourceFieldsJson(item.sourceFields());
        boolean sourceFieldsNeedRepair = !created && !Objects.equals(entity.attributesJson, sourceFieldsJson);
        LocalDateTime now = now();
        if (created) initializeDocument(entity, tenantId, binding.targetId(), item.number(), item.sourceId(), now);
        if (created || binding.changed() || sourceFieldsNeedRepair) {
            entity.sourceSupplierId = item.supplierSourceId();
            entity.sourceWarehouseId = item.warehouseSourceId();
            entity.supplierId = supplierId(tenantId, item.supplierSourceId(), item.supplierCode());
            entity.warehouseId = warehouseId(tenantId, item.warehouseSourceId(), item.warehouseCode());
            entity.supplierCodeSnapshot = item.supplierCode(); entity.supplierNameSnapshot = item.supplierName();
            entity.warehouseCodeSnapshot = item.warehouseCode(); entity.warehouseNameSnapshot = item.warehouseName();
            entity.staffSourceId = item.staffSourceId(); entity.staffName = item.staffName();
            entity.sourceStatus = item.sourceStatus(); entity.sourceStatusName = item.sourceStatusName();
            entity.sourcePaymentStatus = item.paymentStatus(); entity.sourcePaymentName = item.paymentStatusName();
            entity.deliveryAt = local(item.deliveryAt()); entity.sourceCreatedAt = local(item.sourceCreatedAt());
            entity.sourceUpdatedAt = local(item.sourceUpdatedAt()); entity.totalAmount = zero(item.totalAmount());
            entity.paidAmount = zero(item.paidAmount()); entity.goodsCount = zero(item.goodsCount());
            entity.sourceDownloaded = item.downloaded(); entity.remark = item.remark();
            entity.internalCommunication = item.internalCommunication(); entity.attributesJson = sourceFieldsJson;
            entity.sourceSyncedAt = now; entity.updatedAt = now;
            if (!created && binding.changed()) entity.version = next(entity.version);
            if (created) purchaseOrderMapper.insert(entity); else purchaseOrderMapper.updateById(entity);
            if (created || binding.changed() || sourceFieldsNeedRepair) {
                replacePurchaseLines(tenantId, entity.id, item.lines(), now);
            }
        }
        saveBinding(tenantId, runId, "PURCHASE_ORDER", item.sourceId(), "PURCHASE_ORDER", entity.id,
                item.number(), item.number(), item.sourceStatus(), item.sourceUpdatedAt(), item.payloadHash(), now,
                binding.entity());
        return outcome(created, binding.changed() || sourceFieldsNeedRepair);
    }

    @Transactional
    @Override
    public ImportResult importPurchaseReturn(String tenantId, UUID runId, PurchaseReturn item) {
        if (missing(item.sourceId()) || missing(item.number())) return ImportResult.oneRejected();
        Binding binding = binding(tenantId, "PURCHASE_RETURN", item.sourceId(), "PURCHASE_RETURN", item.payloadHash());
        PurchaseReturnEntity entity = binding.existing() ? purchaseReturnMapper.selectById(binding.targetId()) : null;
        boolean created = entity == null;
        if (entity == null) entity = new PurchaseReturnEntity();
        String sourceFieldsJson = sourceFieldsJson(item.sourceFields());
        boolean sourceFieldsNeedRepair = !created && !Objects.equals(entity.attributesJson, sourceFieldsJson);
        LocalDateTime now = now();
        if (created) {
            entity.id = binding.targetId(); entity.tenantId = tenantId; entity.purchaseReturnNo = item.number();
            entity.sourceReturnId = item.sourceId(); entity.internalStatus = "DRAFT";
            entity.ownershipState = EXTERNAL_PRIMARY; entity.recordOrigin = "IMPORTED";
            entity.version = 0L; entity.createdAt = now;
        }
        if (created || binding.changed() || sourceFieldsNeedRepair) {
            entity.sourceSupplierId = item.supplierSourceId();
            entity.sourceWarehouseId = item.warehouseSourceId();
            entity.supplierId = supplierId(tenantId, item.supplierSourceId(), item.supplierCode());
            entity.warehouseId = warehouseId(tenantId, item.warehouseSourceId(), item.warehouseCode());
            entity.supplierCodeSnapshot = item.supplierCode(); entity.supplierNameSnapshot = item.supplierName();
            entity.warehouseCodeSnapshot = item.warehouseCode(); entity.warehouseNameSnapshot = item.warehouseName();
            entity.staffSourceId = item.staffSourceId(); entity.staffName = item.staffName();
            entity.sourceStatus = item.sourceStatus(); entity.sourceStatusName = item.sourceStatusName();
            entity.returnAmount = zero(item.returnAmount()); entity.discountAmount = zero(item.discountAmount());
            entity.returnReason = item.reason(); entity.sourceCreatedAt = local(item.sourceCreatedAt());
            entity.returnSendAt = local(item.sendAt()); entity.internalCommunication = item.internalCommunication();
            entity.remark = item.remark(); entity.detailCount = item.detailCount() == null ? item.lines().size() : item.detailCount();
            entity.contactName = item.contactName(); entity.contactPhone = item.contactPhone();
            entity.contactAddress = item.contactAddress(); entity.cityIdsJson = jsonList(item.cityIds());
            entity.cityNamesJson = jsonList(item.cityNames()); entity.sourceDevice = item.sourceDevice();
            entity.parentReturnSourceId = item.parentReturnSourceId();
            entity.parentCompanySourceId = item.parentCompanySourceId(); entity.sourceDownloaded = item.downloaded();
            entity.attributesJson = sourceFieldsJson; entity.sourceSyncedAt = now; entity.updatedAt = now;
            if (!created && binding.changed()) entity.version = next(entity.version);
            if (created) purchaseReturnMapper.insert(entity); else purchaseReturnMapper.updateById(entity);
            if (created || binding.changed() || sourceFieldsNeedRepair) {
                replaceReturnLines(tenantId, entity.id, item.lines(), now);
            }
        }
        saveBinding(tenantId, runId, "PURCHASE_RETURN", item.sourceId(), "PURCHASE_RETURN", entity.id,
                item.number(), item.number(), item.sourceStatus(), item.sourceCreatedAt(), item.payloadHash(), now,
                binding.entity());
        return outcome(created, binding.changed() || sourceFieldsNeedRepair);
    }

    @Transactional
    @Override
    public ImportResult importWarehousingReceipt(String tenantId, UUID runId, WarehousingReceipt item) {
        if (missing(item.sourceId()) || missing(item.number())) return ImportResult.oneRejected();
        Binding binding = binding(tenantId, "WAREHOUSING_RECEIPT", item.sourceId(),
                "WAREHOUSING_RECEIPT", item.payloadHash());
        WarehousingReceiptEntity entity = binding.existing() ? warehousingReceiptMapper.selectById(binding.targetId()) : null;
        boolean created = entity == null;
        if (entity == null) entity = new WarehousingReceiptEntity();
        String sourceFieldsJson = sourceFieldsJson(item.sourceFields());
        boolean sourceFieldsNeedRepair = !created && !Objects.equals(entity.attributesJson, sourceFieldsJson);
        LocalDateTime now = now();
        if (created) {
            entity.id = binding.targetId(); entity.tenantId = tenantId; entity.warehousingNo = item.number();
            entity.sourceWarehousingId = item.sourceId(); entity.internalStatus = "DRAFT";
            entity.ownershipState = EXTERNAL_PRIMARY; entity.recordOrigin = "IMPORTED";
            entity.version = 0L; entity.createdAt = now;
        }
        if (created || binding.changed() || sourceFieldsNeedRepair) {
            entity.sourceWarehouseId = item.warehouseSourceId();
            entity.sourceSupplierId = item.supplierSourceId();
            entity.warehouseId = warehouseId(tenantId, item.warehouseSourceId(), null);
            entity.supplierId = supplierId(tenantId, item.supplierSourceId(), null);
            entity.warehouseNameSnapshot = item.warehouseName(); entity.supplierNameSnapshot = item.supplierName();
            entity.sourceTypeId = item.typeId(); entity.sourceTypeName = item.typeName();
            entity.sourceStatus = item.sourceStatus(); entity.sourceStatusName = item.sourceStatusName();
            entity.staffName = item.staffName(); entity.clientSourceId = item.clientSourceId();
            entity.accountSourceId = item.accountSourceId(); entity.collaboratorSourceId = item.collaboratorSourceId();
            entity.collaboratorName = item.collaboratorName(); entity.logisticsSourceId = item.logisticsSourceId();
            entity.expressNumber = item.expressNumber(); entity.storageAt = local(item.storageAt());
            entity.sourceCreatedAt = local(item.sourceCreatedAt()); entity.sourceUpdatedAt = local(item.sourceUpdatedAt());
            entity.freightAmount = zero(item.freightAmount()); entity.totalAmount = zero(item.totalAmount());
            entity.costAmount = zero(item.costAmount()); entity.sourceApiFlag = item.apiFlag();
            entity.splitType = item.splitType(); entity.remark = item.remark();
            entity.attributesJson = sourceFieldsJson; entity.sourceSyncedAt = now; entity.updatedAt = now;
            if (!created && binding.changed()) entity.version = next(entity.version);
            if (created) warehousingReceiptMapper.insert(entity); else warehousingReceiptMapper.updateById(entity);
            if (created || binding.changed() || sourceFieldsNeedRepair) {
                replaceWarehousingLines(tenantId, entity.id, item.lines(), now);
                replacePurchaseLinks(tenantId, entity.id, item.purchaseLinks(), now);
            }
        }
        saveBinding(tenantId, runId, "WAREHOUSING_RECEIPT", item.sourceId(), "WAREHOUSING_RECEIPT",
                entity.id, item.number(), item.number(), item.sourceStatus(), item.sourceUpdatedAt(),
                item.payloadHash(), now, binding.entity());
        return outcome(created, binding.changed() || sourceFieldsNeedRepair);
    }

    @Transactional
    @Override
    public ImportResult importInventory(String tenantId, UUID runId, InventoryBalance item) {
        if (missing(item.goodsCode()) || missing(item.warehouseCode())) return ImportResult.oneRejected();
        String warehouseKey = first(item.warehouseGuid(), item.warehouseCode());
        String productKey = first(item.goodsGuid(), item.goodsCode());
        String variantKey = variantKey(item);
        InventoryBalanceEntity entity = inventoryBalanceMapper.selectOne(
                Wrappers.<InventoryBalanceEntity>query().eq("tenant_id", tenantId)
                        .eq("source_warehouse_key", warehouseKey).eq("source_product_key", productKey)
                        .eq("source_variant_key", variantKey).last("LIMIT 1"));
        boolean created = entity == null;
        String sourceFieldsJson = sourceFieldsJson(item.sourceFields());
        boolean changed = created || !Objects.equals(entity.attributesJson, sourceFieldsJson);
        LocalDateTime now = now();
        if (!created && !changed) return ImportResult.oneDuplicate();
        if (entity == null) {
            entity = new InventoryBalanceEntity(); entity.id = UUID.randomUUID().toString();
            entity.tenantId = tenantId; entity.sourceWarehouseKey = warehouseKey;
            entity.sourceProductKey = productKey; entity.sourceVariantKey = variantKey;
            entity.reservedQuantity = BigDecimal.ZERO; entity.inTransitQuantity = BigDecimal.ZERO;
            entity.calculationOrigin = "DHB_SNAPSHOT"; entity.version = 0L; entity.createdAt = now;
        }
        entity.warehouseId = warehouseId(tenantId, null, item.warehouseCode());
        entity.spuId = productTarget(tenantId, "PRODUCT_SPU", item.goodsCode());
        entity.skuId = missing(item.firstOptionCode()) && missing(item.secondOptionCode()) ? null
                : productTarget(tenantId, "PRODUCT_SKU", skuCode(item));
        entity.sourceWarehouseGuid = item.warehouseGuid(); entity.sourceWarehouseCode = item.warehouseCode();
        entity.sourceWarehouseName = item.warehouseName(); entity.sourceGoodsGuid = item.goodsGuid();
        entity.sourceGoodsCode = item.goodsCode(); entity.sourceGoodsName = item.goodsName();
        entity.firstOptionGuid = item.firstOptionGuid(); entity.firstOptionCode = item.firstOptionCode();
        entity.firstOptionName = item.firstOptionName(); entity.secondOptionGuid = item.secondOptionGuid();
        entity.secondOptionCode = item.secondOptionCode(); entity.secondOptionName = item.secondOptionName();
        entity.realQuantity = zero(item.realQuantity()); entity.availableQuantity = zero(item.availableQuantity());
        entity.attributesJson = sourceFieldsJson; entity.sourceSyncedAt = now; entity.updatedAt = now;
        if (!created && changed) entity.version = next(entity.version);
        if (created) inventoryBalanceMapper.insert(entity); else inventoryBalanceMapper.updateById(entity);
        return outcome(created, changed);
    }

    @Transactional
    @Override
    public void reconcileSourcePresence(String tenantId, UUID runId,
                                        Map<String, Set<String>> seenSourceIds) {
        LocalDateTime now = now();
        seenSourceIds.forEach((sourceType, seenIds) -> {
            Set<String> seen = seenIds == null ? Set.of() : seenIds;
            List<MasterSourceBindingEntity> bindings = bindingMapper.selectList(
                    Wrappers.<MasterSourceBindingEntity>query()
                            .eq("tenant_id", tenantId)
                            .eq("source_system", SOURCE_SYSTEM)
                            .eq("source_object_type", sourceType));
            for (MasterSourceBindingEntity binding : bindings) {
                boolean present = seen.contains(binding.sourceObjectId);
                String desired = present ? "PRESENT" : "SOURCE_ABSENT";
                if (Objects.equals(desired, binding.sourcePresence)) continue;
                binding.sourcePresence = desired;
                binding.sourceAbsentAt = present ? null : now;
                binding.lastSyncRunId = runId.toString();
                binding.version = next(binding.version);
                binding.updatedAt = now;
                bindingMapper.updateById(binding);
            }
        });
    }

    @Transactional
    @Override public void completeRun(String tenantId, UUID runId, RunStatistics value) {
        finishRun(tenantId, runId, "SUCCEEDED", value, null, null);
    }

    @Transactional
    @Override public void failRun(String tenantId, UUID runId, RunStatistics value, RuntimeException error) {
        String message = error.getMessage();
        if (message != null && message.length() > 2000) message = message.substring(0, 2000);
        finishRun(tenantId, runId, "FAILED", value, error.getClass().getSimpleName(), message);
    }

    private void initializeSupplier(SupplierEntity entity, String tenantId, String id,
                                    Supplier item, LocalDateTime now) {
        entity.id = id; entity.tenantId = tenantId;
        entity.supplierCode = uniqueSupplierCode(tenantId, item.code(), item.sourceId());
        entity.sourceSupplierId = item.sourceId(); entity.internalStatus = "ACTIVE";
        entity.ownershipState = EXTERNAL_PRIMARY; entity.recordOrigin = "IMPORTED";
        entity.version = 0L; entity.createdAt = now;
    }

    private void initializeDocument(PurchaseOrderEntity entity, String tenantId, String id,
                                    String number, String sourceId, LocalDateTime now) {
        entity.id = id; entity.tenantId = tenantId; entity.purchaseOrderNo = number;
        entity.sourcePurchaseId = sourceId; entity.internalStatus = "DRAFT";
        entity.ownershipState = EXTERNAL_PRIMARY; entity.recordOrigin = "IMPORTED";
        entity.version = 0L; entity.createdAt = now;
    }

    private void replacePurchaseLines(String tenantId, String orderId,
                                      List<PurchaseOrder.Line> lines, LocalDateTime now) {
        purchaseOrderLineMapper.delete(Wrappers.<PurchaseOrderLineEntity>query()
                .eq("tenant_id", tenantId).eq("purchase_order_id", orderId));
        for (PurchaseOrder.Line line : lines) {
            if (missing(line.sourceLineId())) continue;
            PurchaseOrderLineEntity entity = new PurchaseOrderLineEntity();
            entity.id = UUID.randomUUID().toString(); entity.tenantId = tenantId; entity.purchaseOrderId = orderId;
            entity.sourceLineId = line.sourceLineId(); entity.spuId = productTarget(tenantId, "PRODUCT_SPU", line.goodsCode());
            entity.skuId = productTarget(tenantId, "PRODUCT_SKU", line.optionsGoodsCode());
            entity.sourceGoodsId = line.sourceGoodsId(); entity.sourceGoodsGuid = line.sourceGoodsGuid();
            entity.sourceGoodsCode = line.goodsCode(); entity.sourceGoodsName = line.goodsName();
            entity.sourceOptionsId = line.optionsId(); entity.sourceOptionsGoodsCode = line.optionsGoodsCode();
            entity.optionsSummary = line.optionsSummary(); entity.baseQuantity = zero(line.baseQuantity());
            entity.unitPrice = zero(line.unitPrice()); entity.purchaseUnitCode = line.unitCode();
            entity.purchaseUnitName = line.unitName(); entity.purchaseUnitQuantity = zero(line.unitQuantity());
            entity.warehousedQuantity = zero(line.warehousedQuantity());
            entity.returnedQuantity = zero(line.returnedQuantity()); entity.remark = line.remark();
            entity.attributesJson = sourceFieldsJson(line.sourceFields()); entity.createdAt = now; entity.updatedAt = now;
            purchaseOrderLineMapper.insert(entity);
        }
    }

    private void replaceReturnLines(String tenantId, String returnId,
                                    List<PurchaseReturn.Line> lines, LocalDateTime now) {
        purchaseReturnLineMapper.delete(Wrappers.<PurchaseReturnLineEntity>query()
                .eq("tenant_id", tenantId).eq("purchase_return_id", returnId));
        for (PurchaseReturn.Line line : lines) {
            if (missing(line.sourceLineId())) continue;
            PurchaseReturnLineEntity entity = new PurchaseReturnLineEntity();
            entity.id = UUID.randomUUID().toString(); entity.tenantId = tenantId;
            entity.purchaseReturnId = returnId; entity.sourceLineId = line.sourceLineId();
            entity.spuId = productTarget(tenantId, "PRODUCT_SPU", line.goodsCode());
            entity.skuId = productTarget(tenantId, "PRODUCT_SKU", line.optionsGoodsCode());
            entity.sourceGoodsId = line.sourceGoodsId(); entity.sourceGoodsCode = line.goodsCode();
            entity.sourceGoodsName = line.goodsName(); entity.sourceOptionsId = line.optionsId();
            entity.sourceOptionsGoodsCode = line.optionsGoodsCode(); entity.optionsSummary = line.optionsSummary();
            entity.requestedQuantity = zero(line.requestedQuantity());
            entity.confirmedQuantity = zero(line.confirmedQuantity()); entity.returnPrice = zero(line.returnPrice());
            entity.confirmedPrice = zero(line.confirmedPrice()); entity.returnUnitCode = line.unitCode();
            entity.returnUnitName = line.unitName(); entity.returnUnitQuantity = zero(line.unitQuantity());
            entity.confirmedUnitQuantity = zero(line.confirmedUnitQuantity());
            entity.conversionNumber = zero(line.conversionNumber()); entity.amount = zero(line.amount());
            entity.costPrice = zero(line.costPrice()); entity.purchaseOrderNo = line.purchaseOrderNo();
            entity.categoryNameSnapshot = line.categoryName(); entity.brandNameSnapshot = line.brandName();
            entity.remark = line.remark(); entity.attributesJson = sourceFieldsJson(line.sourceFields());
            entity.createdAt = now; entity.updatedAt = now; purchaseReturnLineMapper.insert(entity);
        }
    }

    private void replaceWarehousingLines(String tenantId, String receiptId,
                                         List<WarehousingReceipt.Line> lines, LocalDateTime now) {
        warehousingReceiptLineMapper.delete(Wrappers.<WarehousingReceiptLineEntity>query()
                .eq("tenant_id", tenantId).eq("warehousing_receipt_id", receiptId));
        for (WarehousingReceipt.Line line : lines) {
            if (missing(line.sourceLineId())) continue;
            WarehousingReceiptLineEntity entity = new WarehousingReceiptLineEntity();
            entity.id = UUID.randomUUID().toString(); entity.tenantId = tenantId;
            entity.warehousingReceiptId = receiptId; entity.sourceLineId = line.sourceLineId();
            entity.spuId = productTarget(tenantId, "PRODUCT_SPU", line.goodsCode());
            entity.skuId = productTarget(tenantId, "PRODUCT_SKU", line.optionsGoodsCode());
            entity.sourceGoodsId = line.sourceGoodsId(); entity.sourceGoodsCode = line.goodsCode();
            entity.sourceGoodsName = line.goodsName(); entity.sourceOptionsId = line.optionsId();
            entity.sourceOptionsGoodsCode = line.optionsGoodsCode(); entity.optionsSummary = line.optionsSummary();
            entity.baseQuantity = zero(line.baseQuantity()); entity.unitQuantity = zero(line.unitQuantity());
            entity.unitCode = line.unitCode(); entity.unitName = line.unitName();
            entity.conversionNumber = zero(line.conversionNumber()); entity.costPrice = zero(line.costPrice());
            entity.unitCostPrice = zero(line.unitCostPrice()); entity.purchasePrice = zero(line.purchasePrice());
            entity.wholesalePrice = zero(line.wholesalePrice()); entity.allocation = line.allocation();
            entity.barcode = line.barcode(); entity.goodsModel = line.goodsModel();
            entity.sourceRealQuantity = line.sourceRealQuantity();
            entity.sourceAvailableQuantity = line.sourceAvailableQuantity();
            entity.collaboratorSourceId = line.collaboratorSourceId(); entity.collaboratorName = line.collaboratorName();
            entity.remark = line.remark(); entity.attributesJson = sourceFieldsJson(line.sourceFields());
            entity.createdAt = now; entity.updatedAt = now; warehousingReceiptLineMapper.insert(entity);
        }
    }

    private void replacePurchaseLinks(String tenantId, String receiptId,
                                      List<WarehousingReceipt.PurchaseLink> links, LocalDateTime now) {
        warehousingPurchaseLinkMapper.delete(Wrappers.<WarehousingPurchaseLinkEntity>query()
                .eq("tenant_id", tenantId).eq("warehousing_receipt_id", receiptId));
        for (WarehousingReceipt.PurchaseLink link : links) {
            if (missing(link.purchaseOrderNo())) continue;
            PurchaseOrderEntity order = purchaseOrderMapper.selectOne(Wrappers.<PurchaseOrderEntity>query()
                    .eq("tenant_id", tenantId).eq("purchase_order_no", link.purchaseOrderNo()).last("LIMIT 1"));
            WarehousingPurchaseLinkEntity entity = new WarehousingPurchaseLinkEntity();
            entity.id = UUID.randomUUID().toString(); entity.tenantId = tenantId;
            entity.warehousingReceiptId = receiptId; entity.purchaseOrderId = order == null ? null : order.id;
            entity.sourcePurchaseId = link.sourcePurchaseId(); entity.purchaseOrderNo = link.purchaseOrderNo();
            entity.createdAt = now; entity.updatedAt = now; warehousingPurchaseLinkMapper.insert(entity);
        }
    }

    private Binding binding(String tenantId, String sourceType, String sourceId,
                            String targetType, String payloadHash) {
        MasterSourceBindingEntity entity = bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType).eq("source_object_id", sourceId).last("LIMIT 1"));
        return new Binding(entity, entity == null ? UUID.randomUUID().toString() : entity.targetId,
                entity == null || !Objects.equals(entity.sourcePayloadHash, requiredHash(payloadHash))
                        || "SOURCE_ABSENT".equals(entity.sourcePresence),
                entity != null);
    }

    private void saveBinding(String tenantId, UUID runId, String sourceType, String sourceId,
                             String targetType, String targetId, String code, String name,
                             String status, Instant sourceUpdatedAt, String payloadHash,
                             LocalDateTime now, MasterSourceBindingEntity existing) {
        String normalizedHash = requiredHash(payloadHash);
        if (existing != null && Objects.equals(existing.sourcePayloadHash, normalizedHash)
                && !"SOURCE_ABSENT".equals(existing.sourcePresence)) return;
        MasterSourceBindingEntity entity = existing == null ? new MasterSourceBindingEntity() : existing;
        if (existing == null) {
            entity.id = UUID.randomUUID().toString(); entity.tenantId = tenantId;
            entity.sourceSystem = SOURCE_SYSTEM; entity.sourceObjectType = sourceType;
            entity.sourceObjectId = sourceId; entity.targetType = targetType; entity.targetId = targetId;
            entity.version = 0L; entity.createdAt = now;
        } else entity.version = next(entity.version);
        entity.sourceCode = code; entity.sourceName = name; entity.sourceStatus = status;
        entity.sourceUpdatedAt = local(sourceUpdatedAt); entity.sourcePayloadHash = normalizedHash;
        entity.sourcePresence = "PRESENT"; entity.sourceAbsentAt = null;
        entity.lastSyncRunId = runId.toString(); entity.syncedAt = now; entity.updatedAt = now;
        if (existing == null) bindingMapper.insert(entity); else bindingMapper.updateById(entity);
    }

    private String supplierId(String tenantId, String sourceId, String code) {
        SupplierEntity item = !missing(sourceId) ? supplierMapper.selectOne(Wrappers.<SupplierEntity>query()
                .eq("tenant_id", tenantId).eq("source_supplier_id", sourceId).last("LIMIT 1")) : null;
        if (item == null && !missing(code)) item = supplierMapper.selectOne(Wrappers.<SupplierEntity>query()
                .eq("tenant_id", tenantId).eq("supplier_code", code).last("LIMIT 1"));
        return item == null ? null : item.id;
    }

    private String warehouseId(String tenantId, String sourceId, String code) {
        WarehouseEntity item = !missing(sourceId) ? warehouseMapper.selectOne(Wrappers.<WarehouseEntity>query()
                .eq("tenant_id", tenantId).eq("source_warehouse_id", sourceId).last("LIMIT 1")) : null;
        if (item == null && !missing(code)) item = warehouseMapper.selectOne(Wrappers.<WarehouseEntity>query()
                .eq("tenant_id", tenantId).eq("warehouse_code", code).last("LIMIT 1"));
        return item == null ? null : item.id;
    }

    private String sourceSupplierId(String tenantId, String localId) {
        if (missing(localId)) return null;
        SupplierEntity item = supplierMapper.selectOne(Wrappers.<SupplierEntity>query()
                .eq("tenant_id", tenantId).eq("id", localId).last("LIMIT 1"));
        return item == null ? null : item.sourceSupplierId;
    }

    private String sourceWarehouseId(String tenantId, String localId) {
        if (missing(localId)) return null;
        WarehouseEntity item = warehouseMapper.selectOne(Wrappers.<WarehouseEntity>query()
                .eq("tenant_id", tenantId).eq("id", localId).last("LIMIT 1"));
        return item == null ? null : item.sourceWarehouseId;
    }

    private String productTarget(String tenantId, String sourceType, String sourceCode) {
        if (missing(sourceCode)) return null;
        MasterSourceBindingEntity item = bindingMapper.selectOne(Wrappers.<MasterSourceBindingEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("source_object_type", sourceType).eq("source_code", sourceCode).last("LIMIT 1"));
        return item == null ? null : item.targetId;
    }

    private static PurchaseOrderView purchaseOrderView(PurchaseOrderEntity item, int lineCount) {
        return new PurchaseOrderView(item.id, item.sourcePurchaseId, item.purchaseOrderNo,
                item.sourceSupplierId, item.supplierCodeSnapshot, item.supplierNameSnapshot,
                item.sourceWarehouseId, item.warehouseCodeSnapshot, item.warehouseNameSnapshot,
                item.staffSourceId, item.staffName, item.sourceStatus, item.sourceStatusName,
                item.sourcePaymentStatus, item.sourcePaymentName, item.internalStatus, item.totalAmount,
                item.paidAmount, item.goodsCount, item.sourceDownloaded, item.remark,
                item.internalCommunication, instant(item.deliveryAt), instant(item.sourceCreatedAt),
                instant(item.sourceUpdatedAt), lineCount, instant(item.sourceSyncedAt));
    }

    private static PurchaseOrderLineView purchaseOrderLine(PurchaseOrderLineEntity item) {
        return new PurchaseOrderLineView(item.sourceLineId, item.sourceGoodsId, item.sourceGoodsGuid,
                item.sourceGoodsCode, item.sourceGoodsName, item.sourceOptionsId,
                item.sourceOptionsGoodsCode, item.optionsSummary, item.baseQuantity, item.unitPrice,
                item.purchaseUnitCode, item.purchaseUnitName, item.purchaseUnitQuantity,
                item.warehousedQuantity, item.returnedQuantity, item.remark,
                parseSourceFields(item.attributesJson));
    }

    private static PurchaseReturnView purchaseReturnView(PurchaseReturnEntity item, int lineCount) {
        return new PurchaseReturnView(item.id, item.sourceReturnId, item.purchaseReturnNo,
                item.sourceSupplierId, item.supplierCodeSnapshot, item.supplierNameSnapshot,
                item.sourceWarehouseId, item.warehouseCodeSnapshot, item.warehouseNameSnapshot,
                item.staffSourceId, item.staffName, item.sourceStatus, item.sourceStatusName,
                item.internalStatus, item.returnAmount, item.discountAmount, item.returnReason,
                instant(item.sourceCreatedAt), instant(item.returnSendAt), item.internalCommunication,
                item.remark, item.detailCount, item.contactName, item.contactPhone,
                item.contactAddress, parseJsonList(item.cityIdsJson), parseJsonList(item.cityNamesJson),
                item.sourceDevice, item.parentReturnSourceId, item.parentCompanySourceId,
                item.sourceDownloaded, lineCount, instant(item.sourceSyncedAt));
    }

    private static WarehousingLineView warehousingLine(WarehousingReceiptLineEntity item) {
        return new WarehousingLineView(item.sourceLineId, item.sourceGoodsId, item.sourceGoodsCode,
                item.sourceGoodsName, item.sourceOptionsId, item.sourceOptionsGoodsCode,
                item.optionsSummary, item.baseQuantity, item.unitQuantity, item.unitCode, item.unitName,
                item.conversionNumber, item.costPrice, item.unitCostPrice, item.purchasePrice,
                item.wholesalePrice, item.allocation, item.barcode, item.goodsModel,
                item.sourceRealQuantity, item.sourceAvailableQuantity, item.collaboratorSourceId,
                item.collaboratorName, item.remark, parseSourceFields(item.attributesJson));
    }

    private static PurchaseReturnLineView purchaseReturnLine(PurchaseReturnLineEntity item) {
        return new PurchaseReturnLineView(item.sourceLineId, item.sourceGoodsId, item.sourceGoodsCode,
                item.sourceGoodsName, item.sourceOptionsId, item.sourceOptionsGoodsCode,
                item.optionsSummary, item.requestedQuantity, item.confirmedQuantity, item.returnPrice,
                item.confirmedPrice, item.returnUnitCode, item.returnUnitName, item.returnUnitQuantity,
                item.confirmedUnitQuantity, item.conversionNumber, item.amount, item.costPrice,
                item.purchaseOrderNo, item.categoryNameSnapshot, item.brandNameSnapshot, item.remark,
                parseSourceFields(item.attributesJson));
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private Map<String, Integer> lineCounts(String tenantId, Set<String> parentIds, String type) {
        if (parentIds.isEmpty()) return Map.of();
        return switch (type) {
            case "PURCHASE" -> purchaseOrderLineMapper.selectList(Wrappers.<PurchaseOrderLineEntity>query()
                    .eq("tenant_id", tenantId).in("purchase_order_id", parentIds)).stream()
                    .collect(Collectors.groupingBy(item -> item.purchaseOrderId,
                            Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
            case "RETURN" -> purchaseReturnLineMapper.selectList(Wrappers.<PurchaseReturnLineEntity>query()
                    .eq("tenant_id", tenantId).in("purchase_return_id", parentIds)).stream()
                    .collect(Collectors.groupingBy(item -> item.purchaseReturnId,
                            Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
            default -> warehousingReceiptLineMapper.selectList(Wrappers.<WarehousingReceiptLineEntity>query()
                    .eq("tenant_id", tenantId).in("warehousing_receipt_id", parentIds)).stream()
                    .collect(Collectors.groupingBy(item -> item.warehousingReceiptId,
                            Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
        };
    }

    private void finishRun(String tenantId, UUID runId, String status, RunStatistics value,
                           String errorCode, String errorMessage) {
        MasterDataSyncRunEntity entity = syncRunMapper.selectOne(Wrappers.<MasterDataSyncRunEntity>query()
                .eq("tenant_id", tenantId).eq("id", runId.toString()).eq("status", "RUNNING")
                .last("LIMIT 1"));
        if (entity == null) return;
        LocalDateTime now = now(); entity.status = status; entity.fetchedCount = value.fetched();
        entity.createdCount = value.created(); entity.changedCount = value.changed();
        entity.duplicateCount = value.duplicates(); entity.rejectedCount = value.rejected();
        entity.errorCode = errorCode; entity.errorMessage = errorMessage; entity.finishedAt = now;
        entity.updatedAt = now; syncRunMapper.updateById(entity);
        syncLockMapper.delete(Wrappers.<MasterDataSyncLockEntity>query()
                .eq("tenant_id", tenantId).eq("run_id", runId.toString()));
    }

    private void acquireRunLock(String tenantId, String objectType, UUID runId, LocalDateTime now) {
        syncLockMapper.delete(Wrappers.<MasterDataSyncLockEntity>query()
                .eq("tenant_id", tenantId).eq("source_system", SOURCE_SYSTEM)
                .eq("object_type", objectType).le("expires_at", now));
        MasterDataSyncLockEntity lock = new MasterDataSyncLockEntity();
        lock.id = UUID.randomUUID().toString(); lock.tenantId = tenantId;
        lock.sourceSystem = SOURCE_SYSTEM; lock.objectType = objectType;
        lock.runId = runId.toString(); lock.acquiredAt = now;
        lock.expiresAt = now.plus(java.time.Duration.ofHours(6));
        try {
            syncLockMapper.insert(lock);
        } catch (DuplicateKeyException exception) {
            throw new com.rigour.shared.core.exception.BusinessException(
                    com.rigour.shared.core.api.ErrorCode.CONFLICT,
                    "当前租户该类 ERP 数据已有同步任务运行中", java.util.List.of());
        }
    }

    private String uniqueSupplierCode(String tenantId, String preferred, String sourceId) {
        String base = missing(preferred) ? "DHB-SUPPLIER-" + shortHash(sourceId) : preferred.strip();
        for (int attempt = 0; attempt <= 100; attempt++) {
            String candidate = codeCandidate(base, sourceId, attempt);
            long count = supplierMapper.selectCount(Wrappers.<SupplierEntity>query()
                    .eq("tenant_id", tenantId).eq("supplier_code", candidate));
            if (count == 0) return candidate;
        }
        throw new IllegalStateException("ERP供应商编码生成失败，无法保证租户内唯一");
    }
    private String uniqueWarehouseCode(String tenantId, String preferred, String sourceId) {
        String base = missing(preferred) ? "DHB-WAREHOUSE-" + shortHash(sourceId) : preferred.strip();
        for (int attempt = 0; attempt <= 100; attempt++) {
            String candidate = codeCandidate(base, sourceId, attempt);
            long count = warehouseMapper.selectCount(Wrappers.<WarehouseEntity>query()
                    .eq("tenant_id", tenantId).eq("warehouse_code", candidate));
            if (count == 0) return candidate;
        }
        throw new IllegalStateException("ERP仓库编码生成失败，无法保证租户内唯一");
    }

    private static ImportResult outcome(boolean created, boolean changed) {
        return created ? ImportResult.oneCreated() : changed ? ImportResult.oneChanged() : ImportResult.oneDuplicate();
    }
    private static <T> void applySearch(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<T> wrapper,
                                         String query, String... columns) {
        if (missing(query)) return;
        String value = query.strip();
        wrapper.and(w -> {
            w.like(columns[0], value);
            for (int index = 1; index < columns.length; index++) w.or().like(columns[index], value);
        });
    }

    private static <T> void applyStatus(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<T> wrapper,
                                         String status, String... columns) {
        if (missing(status)) return;
        String value = status.strip();
        wrapper.and(w -> {
            w.eq(columns[0], value);
            for (int index = 1; index < columns.length; index++) w.or().eq(columns[index], value);
        });
    }

    private static <T> Set<String> ids(List<T> values, Function<T, String> key) {
        return values.stream().map(key).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static String limitSql(int begin, int step) {
        return "LIMIT " + Math.max(0, begin) + "," + Math.max(1, step);
    }
    private static String options(InventoryBalanceEntity item) {
        List<String> values = new ArrayList<>();
        if (!missing(item.firstOptionName)) values.add(item.firstOptionName);
        if (!missing(item.secondOptionName)) values.add(item.secondOptionName);
        return values.isEmpty() ? "基础规格" : String.join(" / ", values);
    }
    private static String variantKey(InventoryBalance item) {
        String first = first(item.firstOptionGuid(), item.firstOptionCode());
        String second = first(item.secondOptionGuid(), item.secondOptionCode());
        return "BASE".equals(first) && "BASE".equals(second) ? "BASE" : first + "|" + second;
    }
    private static String skuCode(InventoryBalance item) {
        return !missing(item.secondOptionCode()) ? item.secondOptionCode() : item.firstOptionCode();
    }
    private static String first(String first, String second) {
        return !missing(first) ? first.strip() : !missing(second) ? second.strip() : "BASE";
    }
    private static String prefer(String primary, String fallback) {
        return missing(primary) ? fallback : primary;
    }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static Long next(Long value) { return value == null ? 1L : value + 1L; }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private static boolean missing(String value) { return value == null || value.isBlank(); }
    private static String warehouseStatus(String value) {
        if ("T".equalsIgnoreCase(value)) return "正常";
        if ("F".equalsIgnoreCase(value)) return "停用";
        return value;
    }
    private static String requiredHash(String value) { return missing(value) ? shortHash("missing") : value; }
    private static String sourceFieldsJson(Map<String, Object> sourceFields) {
        try {
            return SOURCE_FIELDS_MAPPER.writeValueAsString(sourceFields == null ? Map.of() : sourceFields);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订货宝供应链原始字段序列化失败", exception);
        }
    }
    private static Map<String, Object> parseSourceFields(String json) {
        if (missing(json)) return Map.of();
        try {
            Object decoded = SOURCE_FIELDS_MAPPER.readValue(json, Object.class);
            if (!(decoded instanceof Map<?, ?> values)) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订货宝供应链原始字段反序列化失败", exception);
        }
    }
    private static String jsonList(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        return "[\"" + String.join("\",\"", values.stream()
                .map(value -> value.replace("\\", "\\\\").replace("\"", "\\\"")).toList()) + "\"]";
    }
    private static List<String> parseJsonList(String json) {
        if (missing(json)) return List.of();
        String value = json.strip();
        if (value.length() < 2 || value.charAt(0) != '[' || value.charAt(value.length() - 1) != ']') {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                addJsonListValue(values, current);
            } else {
                current.append(character);
            }
        }
        if (escaped) current.append('\\');
        addJsonListValue(values, current);
        return List.copyOf(values);
    }
    private static void addJsonListValue(List<String> values, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) values.add(value);
        current.setLength(0);
    }
    private static String clipped(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
    private static String codeCandidate(String base, String sourceId, int attempt) {
        if (attempt == 0 && base.length() <= 128) return base;
        String suffix = shortHash(sourceId) + (attempt <= 1 ? "" : "-" + attempt);
        return clipped(base, 128 - suffix.length() - 1) + "-" + suffix;
    }
    private static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private record Binding(MasterSourceBindingEntity entity, String targetId,
                           boolean changed, boolean existing) { }
}
