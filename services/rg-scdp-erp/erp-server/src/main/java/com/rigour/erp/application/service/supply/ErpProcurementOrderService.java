package com.rigour.erp.application.service.supply;

import com.rigour.erp.api.v1.model.InternalProcurementOrderCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderLineCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderPageView;
import com.rigour.erp.api.v1.model.InternalProcurementProductView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderLineWrite;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderSearchCriteria;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderWrite;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProductVariantSnapshot;
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.erp.domain.model.supply.PurchaseUnitConversion;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** ERP 采购订单用例；订单头与明细作为一个业务聚合一次性保存。 */
@Service
public final class ErpProcurementOrderService {
    private static final Logger log = LoggerFactory.getLogger(ErpProcurementOrderService.class);
    private static final String READ_PERMISSION = "erp:supply:read";
    private static final String WRITE_PERMISSION = "erp:supply:write";
    private static final String DRAFT = "DRAFT";
    private static final String SUBMITTED = "SUBMITTED";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ErpProcurementOrderStore store;
    private final BusinessCodeGenerator codeGenerator;
    private final java.time.Clock clock;

    @Autowired
    public ErpProcurementOrderService(ErpProcurementOrderStore store, java.time.Clock erpClock) {
        this(store, new BusinessCodeGenerator(), erpClock);
    }

    ErpProcurementOrderService(ErpProcurementOrderStore store, BusinessCodeGenerator codeGenerator) {
        this(store, codeGenerator, java.time.Clock.systemUTC());
    }

    ErpProcurementOrderService(
            ErpProcurementOrderStore store,
            BusinessCodeGenerator codeGenerator,
            java.time.Clock clock) {
        this.clock = clock;
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public InternalProcurementOrderPageView procurementOrders(
            int begin, int step, String procurementNo, Long supplierId, Long targetWarehouseId,
            String statusCode, Instant expectedArrivalFrom, Instant expectedArrivalTo) {
        return procurementOrders(
                begin,
                step,
                procurementNo,
                supplierId,
                targetWarehouseId,
                statusCode,
                expectedArrivalFrom,
                expectedArrivalTo,
                null,
                null,
                null,
                null,
                null);
    }

    public InternalProcurementOrderPageView procurementOrders(
            int begin,
            int step,
            String procurementNo,
            Long supplierId,
            Long targetWarehouseId,
            String statusCode,
            Instant expectedArrivalFrom,
            Instant expectedArrivalTo,
            String supplierCode,
            String targetWarehouseCode,
            String confirmationStatusCode,
            java.time.LocalDate purchaseDateFrom,
            java.time.LocalDate purchaseDateTo) {
        return procurementOrders(begin,step,procurementNo,supplierId,targetWarehouseId,statusCode,expectedArrivalFrom,expectedArrivalTo,supplierCode,targetWarehouseCode,confirmationStatusCode,purchaseDateFrom,purchaseDateTo,null);
    }

    public InternalProcurementOrderPageView procurementOrders(
            int begin,
            int step,
            String procurementNo,
            Long supplierId,
            Long targetWarehouseId,
            String statusCode,
            Instant expectedArrivalFrom,
            Instant expectedArrivalTo,
            String supplierCode,
            String targetWarehouseCode,
            String confirmationStatusCode,
            java.time.LocalDate purchaseDateFrom,
            java.time.LocalDate purchaseDateTo, String sourceDocumentNo) {
        if (confirmationStatusCode != null
                && !Set.of("UNCONFIRMED", "CONFIRMED").contains(confirmationStatusCode))
            throw badRequest("确认状态无效");
        if (purchaseDateFrom != null
                && purchaseDateTo != null
                && purchaseDateFrom.isAfter(purchaseDateTo)) throw badRequest("采购开始日期不能晚于截止日期");
        String tenantId = tenant(permission("erp:procurement:read",READ_PERMISSION));
        ProcurementOrderSearchCriteria criteria =
                new ProcurementOrderSearchCriteria(
                        ErpServiceValidation.text(procurementNo, 50, "procurementNo"),
                        ErpServiceValidation.optionalId(supplierId, "supplierId"),
                        ErpServiceValidation.optionalId(targetWarehouseId, "targetWarehouseId"),
                        ErpServiceValidation.code(statusCode, "statusCode", false),
                        expectedArrivalFrom,
                        expectedArrivalTo,
                        ErpServiceValidation.text(supplierCode, 50, "supplierCode"),
                        ErpServiceValidation.text(targetWarehouseCode, 50, "targetWarehouseCode"),
                        confirmationStatusCode,
                        purchaseDateFrom,
                        purchaseDateTo, ErpServiceValidation.text(sourceDocumentNo,100,"sourceDocumentNo"));
        if (expectedArrivalFrom != null && expectedArrivalTo != null
                && expectedArrivalFrom.isAfter(expectedArrivalTo)) {
            throw badRequest("expectedArrivalFrom不能晚于expectedArrivalTo");
        }
        var result = store.procurementOrders(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug(
                "ERP采购订单列表查询完成 tenantId={} procurementNo={} supplierId={} warehouseId={}"
                    + " statusCode={} count={} total={}",
                tenantId,
                ErpServiceValidation.value(criteria.procurementNo()),
                criteria.supplierId(),
                criteria.targetWarehouseId(),
                ErpServiceValidation.value(criteria.statusCode()),
                result.items().size(),
                result.total());
        return result;
    }

    public InternalProcurementOrderDetailView procurementOrder(Long id) {
        String tenantId = tenant(permission("erp:procurement:read",READ_PERMISSION));
        InternalProcurementOrderDetailView result = store.procurementOrder(
                        tenantId, ErpServiceValidation.requireId(id, "采购订单ID无效"))
                .orElseThrow(() -> notFound("采购订单不存在"));
        log.debug("ERP采购订单详情查询完成 tenantId={} procurementOrderId={} procurementNo={}",
                tenantId, result.id(), result.procurementNo());
        return result;
    }

    public InternalProcurementOrderDetailView create(InternalProcurementOrderCommand command) {
        CallerIdentity actor = actor(permission("erp:procurement:create",WRITE_PERMISSION));
        String tenantId = actor.tenantId().toString();
        ProcurementOrderWrite normalized = normalize(tenantId, command, false);
        String procurementNo = codeGenerator.generateUnique(ErpBusinessCodeRules.PURCHASE_ORDER,
                candidate -> !store.existsByNo(tenantId, candidate));
        InternalProcurementOrderDetailView created = store.create(
                tenantId, procurementNo, normalized, actor.principalId().toString());
        log.info(
                "ERP采购订单创建完成 tenantId={} procurementOrderId={} procurementNo={} statusCode={}"
                    + " totalAmount={} actorId={}",
                tenantId,
                created.id(),
                created.procurementNo(),
                created.statusCode(),
                created.totalAmount(),
                actor.principalId());
        return created;
    }

    public InternalProcurementOrderDetailView update(Long id, InternalProcurementOrderCommand command) {
        CallerIdentity actor = actor(permission("erp:procurement:update",WRITE_PERMISSION));
        String tenantId = actor.tenantId().toString();
        Long orderId = ErpServiceValidation.requireId(id, "采购订单ID无效");
        ProcurementOrderWrite normalized = normalize(tenantId, command, true);
        InternalProcurementOrderDetailView updated = store.update(
                tenantId, orderId, normalized, actor.principalId().toString());
        log.info(
                "ERP采购订单修改完成 tenantId={} procurementOrderId={} procurementNo={} statusCode={}"
                    + " revision={} actorId={}",
                tenantId,
                updated.id(),
                updated.procurementNo(),
                updated.statusCode(),
                updated.revision(),
                actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(permission("erp:procurement:delete",WRITE_PERMISSION));
        ErpServiceValidation.requireRevision(revision);
        Long orderId = ErpServiceValidation.requireId(id, "采购订单ID无效");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, orderId, revision, actor.principalId().toString());
        log.info("ERP采购订单逻辑删除完成 tenantId={} procurementOrderId={} revision={} actorId={}",
                tenantId, orderId, revision, actor.principalId());
    }

    public InternalProcurementOrderDetailView confirm(Long id, int revision) {
        CallerIdentity actor = actor(permission("erp:procurement:update", WRITE_PERMISSION));
        ErpServiceValidation.requireRevision(revision);
        return store.confirm(
                actor.tenantId().toString(),
                ErpServiceValidation.requireId(id, "采购订单ID无效"),
                revision,
                actor.principalId().toString());
    }

    private ProcurementOrderWrite normalize(String tenantId, InternalProcurementOrderCommand command, boolean update) {
        if (command == null) throw badRequest("采购订单参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        if (command.freightAmount() == null) throw badRequest("请填写物流费，无运费填0");
        BigDecimal freight = decimal(command.freightAmount(), "物流费", 2);
        if (freight.signum() < 0) throw badRequest("物流费不得为负");
        String bearer = ErpServiceValidation.code(command.freightBearerCode(), "运费承担", true);
        if (!Set.of("BUYER", "SUPPLIER", "INCLUDED").contains(bearer)) throw badRequest("运费承担方无效");
        String supplierCode = ErpServiceValidation.text(command.supplierCode(), 50, "supplierCode");
        String warehouseCode =
                ErpServiceValidation.text(command.targetWarehouseCode(), 50, "targetWarehouseCode");
        if (supplierCode == null && command.supplierId() == null) throw badRequest("请选择供应商");
        if (warehouseCode == null && command.targetWarehouseId() == null)
            throw badRequest("请选择入库仓库");
        var supplier =
                store.supplierReference(tenantId, supplierCode, command.supplierId())
                        .orElseThrow(() -> notFound("供应商不存在、已删除或已停用"));
        var warehouse =
                store.warehouseReference(tenantId, warehouseCode, command.targetWarehouseId())
                        .orElseThrow(() -> notFound("入库仓库不存在、已删除或已停用"));
        List<ProcurementOrderLineWrite> lines = lines(tenantId, command.lines());
        // 混合单位没有可相加的总数量；旧字段只在同单位时保留兼容值。
        BigDecimal totalQuantity =
                lines.stream().map(ProcurementOrderLineWrite::unitCode).distinct().count() == 1
                        ? lines.stream()
                                .map(ProcurementOrderLineWrite::quantity)
                                .reduce(ZERO, BigDecimal::add)
                        : ZERO;
        BigDecimal totalAmount =
                decimal(
                        lines.stream()
                                .map(ProcurementOrderLineWrite::lineAmount)
                                .reduce(ZERO, BigDecimal::add),
                        "货款合计",
                        6);
        return new ProcurementOrderWrite(
                supplier.id(),
                warehouse.id(),
                DRAFT,
                command.expectedArrivalTime(),
                totalQuantity,
                totalAmount,
                lines,
                ErpServiceValidation.text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0,
                supplier.code(),
                warehouse.code(),
                command.purchaseDate() == null
                        ? LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))
                        : command.purchaseDate(),
                freight,
                bearer,
                ErpServiceValidation.text(command.contractNo(), 100, "contractNo"),
                ErpServiceValidation.text(command.contractFileKey(), 500, "contractFileKey"),
                requiredReceipt(command.receiverName(), warehouse.contactName(), 100, "收货人"),
                requiredReceipt(command.receiverPhone(), warehouse.contactPhone(), 50, "收货联系电话"),
                requiredReceipt(command.receivingAddress(), warehouse.address(), 1000, "收货地址"));
    }

    private static String requiredReceipt(
            String input, String warehouseValue, int max, String name) {
        String value = ErpServiceValidation.text(input, max, name);
        if (value == null) value = ErpServiceValidation.text(warehouseValue, max, name);
        if (value == null) throw badRequest("请填写" + name);
        return value;
    }

    private List<ProcurementOrderLineWrite> lines(
            String tenantId, List<InternalProcurementOrderLineCommand> source) {
        if (source == null || source.isEmpty()) throw badRequest("采购订单至少需要一条商品明细");
        if (source.size() > 200) throw badRequest("采购订单明细不能超过200条");
        Set<String> duplicateGuard = new LinkedHashSet<>();
        java.util.ArrayList<ProcurementOrderLineWrite> result = new java.util.ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            InternalProcurementOrderLineCommand item = source.get(i);
            if (item == null) continue;
            String productCode = ErpServiceValidation.text(item.productCode(), 50, "productCode");
            String variantCode = ErpServiceValidation.text(item.variantCode(), 50, "variantCode");
            ProductVariantSnapshot snapshot;
            if (productCode != null || variantCode != null) {
                if (productCode == null || variantCode == null) throw badRequest("商品编码和SKU编码不能为空");
                snapshot =
                        store.productVariantByCode(tenantId, productCode, variantCode)
                                .orElseThrow(() -> notFound("采购商品不存在、未提交或SKU不属于该商品"));
            } else {
                Long productId = ErpServiceValidation.requireId(item.productId(), "productId无效");
                Long variantId =
                        ErpServiceValidation.requireId(
                                item.productVariantId(), "productVariantId无效");
                snapshot =
                        store.productVariant(tenantId, productId, variantId)
                                .orElseThrow(() -> notFound("采购商品不存在、未提交或规格已删除"));
            }
            if (!duplicateGuard.add(snapshot.variantCode())) throw badRequest("采购订单明细SKU不能重复");
            String unit = ErpServiceValidation.text(item.unitCode(), 64, "unitCode");
            if (unit == null) unit = snapshot.unitCode();
            BigDecimal rate =
                    PurchaseUnitConversion.factor(
                                    unit,
                                    snapshot.baseUnitCode(),
                                    snapshot.middleUnitCode(),
                                    snapshot.baseToMiddleRate(),
                                    snapshot.bigUnitCode(),
                                    snapshot.baseToBigRate())
                            .orElseThrow(() -> badRequest("采购单位缺少有效换算配置，请先完善商品档案"));
            rate = decimal(rate, "单位换算率", 6);
            BigDecimal quantity = quantity(item.quantity(), "采购数量");
            BigDecimal unitPrice = money(item.unitPrice(), "采购单价");
            decimal(quantity.multiply(rate), "基础采购数量", 6);
            BigDecimal lineAmount =
                    decimal(
                            quantity.multiply(unitPrice).setScale(6, RoundingMode.HALF_UP),
                            "采购金额",
                            6);
            result.add(
                    new ProcurementOrderLineWrite(
                            null,
                            snapshot.productId(),
                            snapshot.productVariantId(),
                            snapshot.productCode(),
                            snapshot.variantCode(),
                            snapshot.productName(),
                            unit,
                            quantity,
                            unitPrice,
                            lineAmount,
                            ErpServiceValidation.text(item.remark(), 1000, "lineRemark"),
                            snapshot.baseUnitCode(),
                            rate));
        }
        if (result.isEmpty()) throw badRequest("采购订单至少需要一条有效商品明细");
        return List.copyOf(result);
    }

    private static BigDecimal quantity(BigDecimal value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        if (value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        return decimal(value, name, 6);
    }

    private static BigDecimal money(BigDecimal value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        if (value.compareTo(ZERO) < 0) throw badRequest(name + "不能小于0");
        return decimal(value, name, 6);
    }

    private static BigDecimal decimal(BigDecimal value, String name, int scale) {
        try {
            BigDecimal normalized = value.setScale(scale, RoundingMode.UNNECESSARY);
            if (normalized.precision() > 24) throw badRequest(name + "超出允许范围");
            return normalized;
        } catch (ArithmeticException e) {
            throw badRequest(name + "最多支持" + scale + "位小数");
        }
    }

    public MasterDataPageView<InternalProcurementProductView> procurementProducts(
            int begin, int step, String productName, String productCode, String variantCode) {
        String tenantId = tenant(permission("erp:procurement:read", READ_PERMISSION));
        return store.procurementProducts(
                tenantId,
                ErpServiceValidation.pageBegin(begin),
                ErpServiceValidation.pageStep(step),
                ErpServiceValidation.text(productName, 200, "productName"),
                ErpServiceValidation.text(productCode, 50, "productCode"),
                ErpServiceValidation.text(variantCode, 50, "variantCode"));
    }

    private static String permission(String action,String legacy){return com.rigour.tenant.iam.client.SupplyAuthorizationContext.current().map(com.rigour.tenant.iam.client.SupplyAuthorizationContext::active).orElse(false)?action:legacy;}

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static String tenant(String permission) {
        return actor(permission).tenantId().toString();
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
