package com.rigour.erp.application.service.supply;

import com.rigour.erp.api.v1.model.InternalProcurementOrderCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderLineCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderLineWrite;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderSearchCriteria;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderWrite;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProductVariantSnapshot;
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    @Autowired
    public ErpProcurementOrderService(ErpProcurementOrderStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpProcurementOrderService(ErpProcurementOrderStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalProcurementOrderSummaryView> procurementOrders(
            int begin, int step, String procurementNo, Long supplierId, Long targetWarehouseId,
            String statusCode, Instant expectedArrivalFrom, Instant expectedArrivalTo) {
        String tenantId = tenant(READ_PERMISSION);
        ProcurementOrderSearchCriteria criteria = new ProcurementOrderSearchCriteria(
                ErpServiceValidation.text(procurementNo, 50, "procurementNo"),
                ErpServiceValidation.optionalId(supplierId, "supplierId"),
                ErpServiceValidation.optionalId(targetWarehouseId, "targetWarehouseId"),
                ErpServiceValidation.code(statusCode, "statusCode", false),
                expectedArrivalFrom, expectedArrivalTo);
        if (expectedArrivalFrom != null && expectedArrivalTo != null
                && expectedArrivalFrom.isAfter(expectedArrivalTo)) {
            throw badRequest("expectedArrivalFrom不能晚于expectedArrivalTo");
        }
        MasterDataPageView<InternalProcurementOrderSummaryView> result = store.procurementOrders(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP采购订单列表查询完成 tenantId={} procurementNo={} supplierId={} warehouseId={} statusCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.procurementNo()), criteria.supplierId(),
                criteria.targetWarehouseId(), ErpServiceValidation.value(criteria.statusCode()),
                result.items().size(), result.total());
        return result;
    }

    public InternalProcurementOrderDetailView procurementOrder(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalProcurementOrderDetailView result = store.procurementOrder(
                        tenantId, ErpServiceValidation.requireId(id, "采购订单ID无效"))
                .orElseThrow(() -> notFound("采购订单不存在"));
        log.debug("ERP采购订单详情查询完成 tenantId={} procurementOrderId={} procurementNo={}",
                tenantId, result.id(), result.procurementNo());
        return result;
    }

    public InternalProcurementOrderDetailView create(InternalProcurementOrderCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        ProcurementOrderWrite normalized = normalize(tenantId, command, false);
        String procurementNo = codeGenerator.generateUnique(ErpBusinessCodeRules.PURCHASE_ORDER,
                candidate -> !store.existsByNo(tenantId, candidate));
        InternalProcurementOrderDetailView created = store.create(
                tenantId, procurementNo, normalized, actor.principalId().toString());
        log.info("ERP采购订单创建完成 tenantId={} procurementOrderId={} procurementNo={} statusCode={} totalAmount={} actorId={}",
                tenantId, created.id(), created.procurementNo(), created.statusCode(),
                created.totalAmount(), actor.principalId());
        return created;
    }

    public InternalProcurementOrderDetailView update(Long id, InternalProcurementOrderCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        Long orderId = ErpServiceValidation.requireId(id, "采购订单ID无效");
        ProcurementOrderWrite normalized = normalize(tenantId, command, true);
        InternalProcurementOrderDetailView updated = store.update(
                tenantId, orderId, normalized, actor.principalId().toString());
        log.info("ERP采购订单修改完成 tenantId={} procurementOrderId={} procurementNo={} statusCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.procurementNo(), updated.statusCode(),
                updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        ErpServiceValidation.requireRevision(revision);
        Long orderId = ErpServiceValidation.requireId(id, "采购订单ID无效");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, orderId, revision, actor.principalId().toString());
        log.info("ERP采购订单逻辑删除完成 tenantId={} procurementOrderId={} revision={} actorId={}",
                tenantId, orderId, revision, actor.principalId());
    }

    private ProcurementOrderWrite normalize(String tenantId, InternalProcurementOrderCommand command, boolean update) {
        if (command == null) throw badRequest("采购订单参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        Long supplierId = ErpServiceValidation.requireId(command.supplierId(), "supplierId无效");
        Long warehouseId = ErpServiceValidation.requireId(command.targetWarehouseId(), "targetWarehouseId无效");
        if (!store.supplierActive(tenantId, supplierId)) throw notFound("供应商不存在、已删除或已停用");
        if (!store.warehouseActive(tenantId, warehouseId)) throw notFound("入库仓库不存在、已删除或已停用");
        List<ProcurementOrderLineWrite> lines = lines(tenantId, command.lines());
        BigDecimal totalQuantity = lines.stream()
                .map(ProcurementOrderLineWrite::quantity)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalAmount = lines.stream()
                .map(ProcurementOrderLineWrite::lineAmount)
                .reduce(ZERO, BigDecimal::add);
        return new ProcurementOrderWrite(supplierId, warehouseId,
                Boolean.TRUE.equals(command.submit()) ? SUBMITTED : DRAFT,
                command.expectedArrivalTime(), totalQuantity, totalAmount, lines,
                ErpServiceValidation.text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
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
            Long productId = ErpServiceValidation.requireId(item.productId(), "productId无效");
            Long variantId = ErpServiceValidation.requireId(item.productVariantId(), "productVariantId无效");
            String duplicateKey = productId + "::" + variantId;
            if (!duplicateGuard.add(duplicateKey)) throw badRequest("采购订单明细商品规格不能重复");
            ProductVariantSnapshot snapshot = store.productVariant(tenantId, productId, variantId)
                    .orElseThrow(() -> notFound("采购商品不存在、未提交或规格已删除"));
            BigDecimal quantity = quantity(item.quantity(), "quantity");
            BigDecimal unitPrice = money(item.unitPrice() == null ? snapshot.purchasePrice() : item.unitPrice(),
                    "unitPrice");
            BigDecimal lineAmount = quantity.multiply(unitPrice);
            result.add(new ProcurementOrderLineWrite(result.size() + 1, productId, variantId,
                    snapshot.productCode(), snapshot.variantCode(), snapshot.productName(), snapshot.unitCode(),
                    quantity, unitPrice, lineAmount, ErpServiceValidation.text(item.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) throw badRequest("采购订单至少需要一条有效商品明细");
        return List.copyOf(result);
    }

    private static BigDecimal quantity(BigDecimal value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        if (value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        return value;
    }

    private static BigDecimal money(BigDecimal value, String name) {
        if (value == null) return ZERO;
        if (value.compareTo(ZERO) < 0) throw badRequest(name + "不能小于0");
        return value;
    }

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
