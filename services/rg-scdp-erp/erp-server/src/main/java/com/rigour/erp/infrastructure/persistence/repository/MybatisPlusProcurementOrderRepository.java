package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderLineView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderSummaryView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderPageView;
import com.rigour.erp.api.v1.model.InternalProcurementProductView;
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
import com.rigour.erp.infrastructure.persistence.mapper.ProcurementReferenceMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    private final ProcurementReadModel readModel;
    private final ProcurementReferenceMapper references;
    private final Clock clock;
    private final ErpWarehouseDataScope scopes;

    public MybatisPlusProcurementOrderRepository(
            InternalProcurementOrderMapper mapper,
            InternalProcurementOrderLineMapper lineMapper,
            InternalSupplierProfileMapper supplierMapper,
            InternalInventoryWarehouseMapper warehouseMapper,
            InternalProductMapper productMapper,
            InternalProductVariantMapper variantMapper,
            Clock erpClock,
            ErpWarehouseDataScope scopes,
            ProcurementReadModel readModel,
            ProcurementReferenceMapper references) {
        this.readModel = readModel;
        this.references = references;
        this.baseMapper = mapper;
        this.lineMapper = lineMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.productMapper = productMapper;
        this.variantMapper = variantMapper;
        this.clock = erpClock;
        this.scopes = scopes;
    }

    @Override
    @Transactional(readOnly = true)
    public InternalProcurementOrderPageView procurementOrders(
            String tenantId, int begin, int step, ProcurementOrderSearchCriteria criteria) {
        InternalProcurementOrderMapper mapper = getBaseMapper();
        long total = mapper.selectCount(query(tenantId, criteria));
        List<InternalProcurementOrderEntity> page = mapper.selectList(query(tenantId, criteria)
                .orderByDesc(InternalProcurementOrderEntity::getCreatedTime)
                .orderByDesc(InternalProcurementOrderEntity::getId)
                .last("LIMIT " + step + " OFFSET " + begin));
        var lineGroups = linesByOrder(tenantId, page);
        var parties = readModel.parties(tenantId, page);
        List<InternalProcurementOrderSummaryView> items =
                page.stream()
                        .map(order -> summary(order, parties.get(order.getId()), lineGroups))
                        .toList();
        Map<String, BigDecimal> quantities = new java.util.LinkedHashMap<>();
        for (var value : mapper.purchaseQuantities(query(tenantId, criteria))) {
            quantities.put(value.unitCode(), value.quantity());
        }
        var totals = new InternalProcurementOrderPageView.Totals(
                quantities, mapper.purchaseAmount(query(tenantId, criteria)));
        return new InternalProcurementOrderPageView(total, begin, step, items, totals);
    }

    @Override
    public Optional<InternalProcurementOrderDetailView> procurementOrder(String tenantId, Long id) {
        if (!scopes.visibleDocument(
                tenantId,
                id,
                "erp_procurement_order",
                "erp:procurement:read",
                "target_warehouse_id")) return Optional.empty();
        return rawDetail(tenantId,id);
    }

    private Optional<InternalProcurementOrderDetailView> rawDetail(String tenantId, Long id) {
        return selectActive(tenantId, id).map(order -> detail(tenantId, order));
    }

    private void requireWarehouse(String tenant,Long id,String action){if(id==null)scopes.requireAll(action);else scopes.requireWarehouse(tenant,id,action);}

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
        return Optional.of(snapshot(product, variant));
    }

    @Override
    public Optional<MasterReference> supplierReference(String tenant, String code, Long legacyId) {
        var q =
                Wrappers.<InternalSupplierProfileEntity>lambdaQuery()
                        .eq(InternalSupplierProfileEntity::getTenantId, tenant)
                        .eq(InternalSupplierProfileEntity::getDeleted, 0)
                        .eq(InternalSupplierProfileEntity::getStatusCode, ACTIVE);
        if (code != null) q.eq(InternalSupplierProfileEntity::getSupplierCode, code);
        if (legacyId != null) q.eq(InternalSupplierProfileEntity::getId, legacyId);
        if (code == null && legacyId == null) return Optional.empty();
        return Optional.ofNullable(supplierMapper.selectOne(q.last("LIMIT 1")))
                .map(s -> new MasterReference(s.getId(), s.getSupplierCode()));
    }

    @Override
    public Optional<MasterReference> warehouseReference(String tenant, String code, Long legacyId) {
        var q =
                Wrappers.<InternalInventoryWarehouseEntity>lambdaQuery()
                        .eq(InternalInventoryWarehouseEntity::getTenantId, tenant)
                        .eq(InternalInventoryWarehouseEntity::getDeleted, 0)
                        .eq(InternalInventoryWarehouseEntity::getStatusCode, ACTIVE);
        if (code != null) q.eq(InternalInventoryWarehouseEntity::getWarehouseCode, code);
        if (legacyId != null) q.eq(InternalInventoryWarehouseEntity::getId, legacyId);
        if (code == null && legacyId == null) return Optional.empty();
        return Optional.ofNullable(warehouseMapper.selectOne(q.last("LIMIT 1")))
                .map(
                        w ->
                                new MasterReference(
                                        w.getId(),
                                        w.getWarehouseCode(),
                                        w.getContactName(),
                                        w.getContactPhone(),
                                        w.getAddress()));
    }

    @Override
    public Optional<ProductVariantSnapshot> productVariantByCode(
            String tenant, String productCode, String variantCode) {
        var p =
                productMapper.selectOne(
                        Wrappers.<InternalProductEntity>lambdaQuery()
                                .eq(InternalProductEntity::getTenantId, tenant)
                                .eq(InternalProductEntity::getProductCode, productCode)
                                .eq(InternalProductEntity::getDeleted, 0)
                                .eq(InternalProductEntity::getSubmitStatusCode, SUBMITTED)
                                .last("LIMIT 1"));
        if (p == null) return Optional.empty();
        var v =
                variantMapper.selectOne(
                        Wrappers.<InternalProductVariantEntity>lambdaQuery()
                                .eq(InternalProductVariantEntity::getTenantId, tenant)
                                .eq(InternalProductVariantEntity::getProductId, p.getId())
                                .eq(InternalProductVariantEntity::getVariantCode, variantCode)
                                .eq(InternalProductVariantEntity::getDeleted, 0)
                                .last("LIMIT 1"));
        return v == null ? Optional.empty() : Optional.of(snapshot(p, v));
    }

    private static ProductVariantSnapshot snapshot(
            InternalProductEntity p, InternalProductVariantEntity v) {
        return new ProductVariantSnapshot(
                p.getId(),
                v.getId(),
                p.getProductCode(),
                v.getVariantCode(),
                p.getProductName(),
                v.getUnitCode() == null ? p.getUnitCode() : v.getUnitCode(),
                v.getPurchasePrice(),
                p.getUnitCode(),
                p.getMiddleUnitCode(),
                p.getBaseToMiddleRate(),
                p.getBigUnitCode(),
                p.getBaseToBigRate(),
                v.getSpecificationSnapshot());
    }

    @Override
    public MasterDataPageView<InternalProcurementProductView> procurementProducts(
            String tenant,
            int begin,
            int step,
            String productName,
            String productCode,
            String variantCode) {
        return new MasterDataPageView<>(
                references.productCount(tenant, productName, productCode, variantCode),
                begin,
                step,
                references.products(tenant, productName, productCode, variantCode, begin, step));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProcurementOrderDetailView create(
            String tenantId, String procurementNo, ProcurementOrderWrite command, String actorId) {
        requireWarehouse(tenantId,command.targetWarehouseId(),"erp:procurement:create");
        if (!com.rigour.erp.domain.model.supply.ProcurementContractKey.writable(
                tenantId, actorId, command.contractFileKey(), null)) throw conflict("采购合同文件引用无效");
        LocalDateTime now = now();
        InternalProcurementOrderEntity entity = orderEntity(tenantId, procurementNo, command, actorId, now);
        try {
            getBaseMapper().insert(entity);
            insertLines(tenantId, entity.getId(), procurementNo, command.lines(), actorId, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("采购订单号已存在或采购订单引用数据无效");
        }
        return rawDetail(tenantId, entity.getId()).orElseThrow(() -> notFound("采购订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProcurementOrderDetailView update(
            String tenantId, Long id, ProcurementOrderWrite command, String actorId) {
        scopes.requireDocument(
                tenantId,
                id,
                "erp_procurement_order",
                "erp:procurement:update",
                "target_warehouse_id");
        requireWarehouse(tenantId,command.targetWarehouseId(),"erp:procurement:update");
        InternalProcurementOrderEntity existing = requireEditable(tenantId, id);
        if (!com.rigour.erp.domain.model.supply.ProcurementContractKey.writable(
                tenantId, actorId, command.contractFileKey(), existing.getContractFileKey()))
            throw conflict("采购合同文件引用无效");
        LocalDateTime now = now();
        int updated =
                getBaseMapper()
                        .update(
                                null,
                                Wrappers.<InternalProcurementOrderEntity>lambdaUpdate()
                                        .set(InternalProcurementOrderEntity::getSupplierId, null)
                                        .set(
                                                InternalProcurementOrderEntity::getSupplierCode,
                                                command.supplierCode())
                                        .set(
                                                InternalProcurementOrderEntity
                                                        ::getTargetWarehouseCode,
                                                command.targetWarehouseCode())
                                        .set(
                                                InternalProcurementOrderEntity::getPurchaseDate,
                                                command.purchaseDate())
                                        .set(
                                                InternalProcurementOrderEntity::getFreightAmount,
                                                command.freightAmount())
                                        .set(
                                                InternalProcurementOrderEntity
                                                        ::getFreightBearerCode,
                                                command.freightBearerCode())
                                        .set(
                                                InternalProcurementOrderEntity::getContractNo,
                                                command.contractNo())
                                        .set(
                                                InternalProcurementOrderEntity::getContractFileKey,
                                                command.contractFileKey())
                                        .set(
                                                InternalProcurementOrderEntity::getReceiverName,
                                                command.receiverName())
                                        .set(
                                                InternalProcurementOrderEntity::getReceiverPhone,
                                                command.receiverPhone())
                                        .set(
                                                InternalProcurementOrderEntity::getReceivingAddress,
                                                command.receivingAddress())
                                        .set(
                                                InternalProcurementOrderEntity
                                                        ::getTargetWarehouseId,
                                                null)
                                        .set(
                                                InternalProcurementOrderEntity::getStatusCode,
                                                command.statusCode())
                                        .set(
                                                InternalProcurementOrderEntity
                                                        ::getExpectedArrivalTime,
                                                local(command.expectedArrivalTime()))
                                        .set(
                                                InternalProcurementOrderEntity::getTotalQuantity,
                                                command.totalQuantity())
                                        .set(
                                                InternalProcurementOrderEntity::getTotalAmount,
                                                command.totalAmount())
                                        .set(
                                                InternalProcurementOrderEntity::getRemark,
                                                command.remark())
                                        .set(
                                                InternalProcurementOrderEntity::getRevision,
                                                command.revision() + 1)
                                        .set(
                                                InternalProcurementOrderEntity::getUpdatedBy,
                                                auditActor(actorId))
                                        .set(InternalProcurementOrderEntity::getUpdatedTime, now)
                                        .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                                        .eq(InternalProcurementOrderEntity::getId, id)
                                        .eq(
                                                InternalProcurementOrderEntity::getRevision,
                                                command.revision())
                                        .eq(
                                                InternalProcurementOrderEntity::getStatusCode,
                                                existing.getStatusCode())
                                        .eq(InternalProcurementOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("采购订单已被其他人修改，请刷新后重试");
        logicDeleteLines(tenantId, id, existing.getProcurementNo(), actorId, now);
        insertLines(tenantId, id, existing.getProcurementNo(), command.lines(), actorId, now);
        return rawDetail(tenantId, id).orElseThrow(() -> notFound("采购订单不存在"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String tenantId, Long id, int revision, String actorId) {
        scopes.requireDocument(
                tenantId,
                id,
                "erp_procurement_order",
                "erp:procurement:delete",
                "target_warehouse_id");
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
        logicDeleteLines(tenantId, id, existing.getProcurementNo(), actorId, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InternalProcurementOrderDetailView confirm(
            String tenantId, Long id, int revision, String actorId) {
        scopes.requireDocument(
                tenantId,
                id,
                "erp_procurement_order",
                "erp:procurement:update",
                "target_warehouse_id");
        var current = requireEditable(tenantId, id);
        var parties = readModel.parties(tenantId, List.of(current)).get(id);
        if (supplierReference(tenantId, parties.supplier().code(), null).isEmpty())
            throw conflict("供应商不存在或已停用");
        if (warehouseReference(tenantId, parties.warehouse().code(), null).isEmpty())
            throw conflict("入库仓库不存在或已停用");
        if (current.getReceiverName() == null
                || current.getReceiverName().isBlank()
                || current.getReceiverPhone() == null
                || current.getReceiverPhone().isBlank()
                || current.getReceivingAddress() == null
                || current.getReceivingAddress().isBlank()) throw conflict("请先编辑补全收货人、收货联系电话和收货地址");
        var lines = readModel.lines(tenantId, current);
        if (lines.isEmpty()) throw conflict("采购订单至少需要一个SKU");
        for (var line : lines) {
            var v = line.view();
            if (v.quantity() == null
                    || v.quantity().signum() <= 0
                    || v.unitPrice() == null
                    || v.unitPrice().signum() < 0) throw conflict("请先编辑补全采购数量和采购单价");
            var product =
                    productVariantByCode(tenantId, v.productCode(), v.variantCode())
                            .orElseThrow(() -> conflict("采购商品已失效，请先编辑核对"));
            if (v.conversionRate() != null) {
                var rate =
                        com.rigour.erp.domain.model.supply.PurchaseUnitConversion.factor(
                                        v.unitCode(),
                                        product.baseUnitCode(),
                                        product.middleUnitCode(),
                                        product.baseToMiddleRate(),
                                        product.bigUnitCode(),
                                        product.baseToBigRate())
                                .orElse(null);
                if (rate == null
                        || !java.util.Objects.equals(v.baseUnitCode(), product.baseUnitCode())
                        || rate.compareTo(v.conversionRate()) != 0)
                    throw conflict("商品单位换算已变化，请先编辑核对采购单位");
            }
        }
        int updated =
                getBaseMapper()
                        .update(
                                null,
                                Wrappers.<InternalProcurementOrderEntity>lambdaUpdate()
                                        .set(
                                                InternalProcurementOrderEntity
                                                        ::getConfirmationStatusCode,
                                                "CONFIRMED")
                                        .set(
                                                InternalProcurementOrderEntity::getStatusCode,
                                                SUBMITTED)
                                        .set(
                                                InternalProcurementOrderEntity::getRevision,
                                                revision + 1)
                                        .set(
                                                InternalProcurementOrderEntity::getUpdatedBy,
                                                auditActor(actorId))
                                        .set(InternalProcurementOrderEntity::getUpdatedTime, now())
                                        .eq(InternalProcurementOrderEntity::getTenantId, tenantId)
                                        .eq(InternalProcurementOrderEntity::getId, id)
                                        .eq(InternalProcurementOrderEntity::getRevision, revision)
                                        .eq(
                                                InternalProcurementOrderEntity
                                                        ::getConfirmationStatusCode,
                                                "UNCONFIRMED")
                                        .eq(InternalProcurementOrderEntity::getStatusCode, DRAFT)
                                        .eq(InternalProcurementOrderEntity::getDeleted, 0));
        if (updated != 1) throw conflict("采购订单已变化或已确认，请刷新后重试");
        return rawDetail(tenantId, id).orElseThrow();
    }

    private InternalProcurementOrderEntity requireEditable(String tenantId, Long id) {
        InternalProcurementOrderEntity existing =
                Optional.ofNullable(
                                getBaseMapper()
                                        .selectOne(
                                                Wrappers
                                                        .<InternalProcurementOrderEntity>
                                                                lambdaQuery()
                                                        .eq(
                                                                InternalProcurementOrderEntity
                                                                        ::getTenantId,
                                                                tenantId)
                                                        .eq(
                                                                InternalProcurementOrderEntity
                                                                        ::getId,
                                                                id)
                                                        .eq(
                                                                InternalProcurementOrderEntity
                                                                        ::getDeleted,
                                                                0)
                                                        .last("LIMIT 1 FOR UPDATE")))
                        .orElseThrow(() -> notFound("采购订单不存在"));
        if (externalSource(existing.getSourceSystemCode())) {
            throw conflict("外部来源采购订单不能编辑或删除");
        }
        if (!DRAFT.equals(existing.getStatusCode())
                || "CONFIRMED".equals(existing.getConfirmationStatusCode())) {
            throw conflict("采购订单已确认，不能编辑或删除");
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
            query.like(InternalProcurementOrderEntity::getProcurementNo, criteria.procurementNo());
        }
        if (criteria.supplierId() != null) {
            supplierReference(tenantId, null, criteria.supplierId())
                    .ifPresentOrElse(
                            r ->
                                    query.eq(
                                            InternalProcurementOrderEntity::getSupplierCode,
                                            r.code()),
                            () -> query.apply("1=0"));
        }
        if (criteria.targetWarehouseId() != null) {
            warehouseReference(tenantId, null, criteria.targetWarehouseId())
                    .ifPresentOrElse(
                            r ->
                                    query.eq(
                                            InternalProcurementOrderEntity::getTargetWarehouseCode,
                                            r.code()),
                            () -> query.apply("1=0"));
        }
        query.eq(
                        criteria.confirmationStatusCode() != null,
                        InternalProcurementOrderEntity::getConfirmationStatusCode,
                        criteria.confirmationStatusCode())
                .ge(
                        criteria.purchaseDateFrom() != null,
                        InternalProcurementOrderEntity::getPurchaseDate,
                        criteria.purchaseDateFrom())
                .le(
                        criteria.purchaseDateTo() != null,
                        InternalProcurementOrderEntity::getPurchaseDate,
                        criteria.purchaseDateTo());
        if (criteria.supplierCode() != null)
            query.eq(InternalProcurementOrderEntity::getSupplierCode, criteria.supplierCode());
        if (criteria.targetWarehouseCode() != null)
            query.eq(
                    InternalProcurementOrderEntity::getTargetWarehouseCode,
                    criteria.targetWarehouseCode());
        if (criteria.statusCode() != null) {
            query.eq(InternalProcurementOrderEntity::getStatusCode, criteria.statusCode());
        }
        if (criteria.expectedArrivalFrom() != null) {
            query.ge(InternalProcurementOrderEntity::getExpectedArrivalTime, local(criteria.expectedArrivalFrom()));
        }
        if (criteria.expectedArrivalTo() != null) {
            query.le(InternalProcurementOrderEntity::getExpectedArrivalTime, local(criteria.expectedArrivalTo()));
        }
        return scopes.apply(query,"erp:procurement:read","target_warehouse_id");
    }

    private InternalProcurementOrderEntity orderEntity(String tenantId, String procurementNo,
                                                       ProcurementOrderWrite command, String actorId,
                                                       LocalDateTime now) {
        InternalProcurementOrderEntity entity = new InternalProcurementOrderEntity();
        entity.setTenantId(tenantId);
        entity.setProcurementNo(procurementNo);
        entity.setSupplierCode(command.supplierCode());
        entity.setTargetWarehouseCode(command.targetWarehouseCode());
        entity.setPurchaseDate(command.purchaseDate());
        entity.setFreightAmount(command.freightAmount());
        entity.setFreightBearerCode(command.freightBearerCode());
        entity.setContractNo(command.contractNo());
        entity.setContractFileKey(command.contractFileKey());
        entity.setReceiverName(command.receiverName());
        entity.setReceiverPhone(command.receiverPhone());
        entity.setReceivingAddress(command.receivingAddress());
        entity.setStatusCode(DRAFT);
        entity.setConfirmationStatusCode("UNCONFIRMED");
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

    private void insertLines(
            String tenantId,
            Long orderId,
            String procurementNo,
            List<ProcurementOrderLineWrite> lines,
            String actorId,
            LocalDateTime now) {
        for (ProcurementOrderLineWrite line : lines) {
            InternalProcurementOrderLineEntity entity = new InternalProcurementOrderLineEntity();
            entity.setTenantId(tenantId);
            entity.setProcurementNo(procurementNo);
            entity.setProductCode(line.productCode());
            entity.setVariantCode(line.variantCode());
            entity.setBaseUnitCode(line.baseUnitCode());
            entity.setConversionRate(line.conversionRate());
            entity.setUnitCode(line.unitCode());
            entity.setQuantity(line.quantity());
            entity.setUnitPrice(line.unitPrice());
            entity.setLineAmount(line.lineAmount());
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

    private void logicDeleteLines(
            String tenantId,
            Long orderId,
            String procurementNo,
            String actorId,
            LocalDateTime now) {
        lineMapper.update(
                null,
                Wrappers.<InternalProcurementOrderLineEntity>lambdaUpdate()
                        .set(InternalProcurementOrderLineEntity::getDeleted, 1)
                        .setSql("revision = revision + 1")
                        .set(InternalProcurementOrderLineEntity::getUpdatedBy, auditActor(actorId))
                        .set(InternalProcurementOrderLineEntity::getUpdatedTime, now)
                        .eq(InternalProcurementOrderLineEntity::getTenantId, tenantId)
                        .and(
                                q ->
                                        q.eq(
                                                        InternalProcurementOrderLineEntity
                                                                ::getProcurementOrderId,
                                                        orderId)
                                                .or()
                                                .eq(
                                                        InternalProcurementOrderLineEntity
                                                                ::getProcurementNo,
                                                        procurementNo))
                        .eq(InternalProcurementOrderLineEntity::getDeleted, 0));
    }

    private InternalProcurementOrderDetailView detail(
            String tenantId, InternalProcurementOrderEntity order) {
        var party = readModel.parties(tenantId, List.of(order)).get(order.getId());
        return new InternalProcurementOrderDetailView(
                order.getId(),
                order.getProcurementNo(),
                order.getSourceSystemCode(),
                order.getSourceDocumentNo(),
                party.supplier().id(),
                party.supplier().name(),
                party.warehouse().id(),
                party.warehouse().name(),
                order.getStatusCode(),
                instant(order.getExpectedArrivalTime()),
                order.getTotalQuantity(),
                order.getTotalAmount(),
                readModel.lines(tenantId, order).stream()
                        .map(ProcurementReadModel.Line::view)
                        .toList(),
                order.getRemark(),
                order.getRevision(),
                order.getCreatedBy(),
                instant(order.getCreatedTime()),
                order.getUpdatedBy(),
                instant(order.getUpdatedTime()),
                party.supplier().code(),
                party.warehouse().code(),
                order.getPurchaseDate(),
                order.getFreightAmount(),
                order.getFreightBearerCode(),
                order.getContractNo(),
                order.getContractFileKey(),
                order.getReceiverName(),
                order.getReceiverPhone(),
                order.getReceivingAddress(),
                order.getConfirmationStatusCode());
    }

    private InternalProcurementOrderSummaryView summary(
            InternalProcurementOrderEntity order,
            ProcurementReadModel.Parties party,
            Map<Long, List<InternalProcurementOrderLineEntity>> groups) {
        var orderLines = groups.getOrDefault(order.getId(), List.of());
        Map<String, BigDecimal> quantities = new java.util.LinkedHashMap<>();
        for (var line : orderLines) {
            if (line.getUnitCode() != null && line.getQuantity() != null) {
                quantities.merge(line.getUnitCode(), line.getQuantity(), BigDecimal::add);
            }
        }
        return new InternalProcurementOrderSummaryView(
                order.getId(),
                order.getProcurementNo(),
                order.getSourceSystemCode(),
                order.getSourceDocumentNo(),
                party.supplier().id(),
                party.supplier().name(),
                party.warehouse().id(),
                party.warehouse().name(),
                order.getStatusCode(),
                instant(order.getExpectedArrivalTime()),
                order.getTotalQuantity(),
                order.getTotalAmount(),
                orderLines.size(),
                order.getRevision(),
                instant(order.getUpdatedTime()),
                party.supplier().code(),
                party.warehouse().code(),
                order.getPurchaseDate(),
                order.getFreightAmount(),
                order.getFreightBearerCode(),
                order.getContractNo(),
                order.getContractFileKey(),
                order.getReceiverName(),
                order.getReceiverPhone(),
                order.getReceivingAddress(),
                order.getConfirmationStatusCode(),
                quantities,
                order.getCreatedBy());
    }

    private Map<Long, List<InternalProcurementOrderLineEntity>> linesByOrder(String tenant, List<InternalProcurementOrderEntity> orders) {
        if (orders.isEmpty()) return Map.of();
        var byNo =
                orders.stream()
                        .collect(
                                Collectors.toMap(
                                        InternalProcurementOrderEntity::getProcurementNo,
                                        InternalProcurementOrderEntity::getId));
        var rows =
                lineMapper.selectList(
                        Wrappers.<InternalProcurementOrderLineEntity>lambdaQuery()
                                .eq(InternalProcurementOrderLineEntity::getTenantId, tenant)
                                .and(
                                        q ->
                                                q.in(
                                                                InternalProcurementOrderLineEntity
                                                                        ::getProcurementNo,
                                                                byNo.keySet())
                                                        .or()
                                                        .in(
                                                                InternalProcurementOrderLineEntity
                                                                        ::getProcurementOrderId,
                                                                byNo.values()))
                                .eq(InternalProcurementOrderLineEntity::getDeleted, 0));
        Map<Long, List<InternalProcurementOrderLineEntity>> groups = new java.util.HashMap<>();
        for (var row : rows) {
            Long id =
                    row.getProcurementNo() == null
                            ? row.getProcurementOrderId()
                            : byNo.get(row.getProcurementNo());
            if (id != null) groups.computeIfAbsent(id, ignored -> new java.util.ArrayList<>()).add(row);
        }
        return groups;
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
