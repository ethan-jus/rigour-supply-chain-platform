package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.InternalSalesStockOutCommand;
import com.rigour.erp.api.v1.model.InternalSalesStockOutLineCommand;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
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
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.erp.domain.enums.ErpStockDocumentStatus;
import com.rigour.erp.domain.enums.ErpStockOutType;
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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** ERP 出库单用例；销售出库确认会生成出库单、扣减库存余额并写库存流水。 */
@Service
public final class ErpStockOutOrderService {
    private static final Logger log = LoggerFactory.getLogger(ErpStockOutOrderService.class);
    private static final String READ_PERMISSION = "erp:supply:read";
    private static final String WRITE_PERMISSION = "erp:supply:write";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ErpStockOutOrderStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpStockOutOrderService(ErpStockOutOrderStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpStockOutOrderService(ErpStockOutOrderStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalStockOutOrderSummaryView> stockOutOrders(
            int begin, int step, String stockOutNo, String stockOutTypeCode, Long warehouseId,
            String salesOrderNo, String transferOrderNo, String customerName, String statusCode,
            Instant stockOutTimeFrom, Instant stockOutTimeTo) {
        String tenantId = tenant(READ_PERMISSION);
        if (stockOutTimeFrom != null && stockOutTimeTo != null && stockOutTimeFrom.isAfter(stockOutTimeTo)) {
            throw badRequest("stockOutTimeFrom不能晚于stockOutTimeTo");
        }
        StockOutOrderSearchCriteria criteria = new StockOutOrderSearchCriteria(
                ErpServiceValidation.text(stockOutNo, 50, "stockOutNo"),
                ErpServiceValidation.code(stockOutTypeCode, "stockOutTypeCode", false),
                ErpServiceValidation.optionalId(warehouseId, "warehouseId"),
                ErpServiceValidation.text(salesOrderNo, 50, "salesOrderNo"),
                ErpServiceValidation.text(transferOrderNo, 50, "transferOrderNo"),
                ErpServiceValidation.text(customerName, 200, "customerName"),
                ErpServiceValidation.code(statusCode, "statusCode", false),
                stockOutTimeFrom, stockOutTimeTo);
        MasterDataPageView<InternalStockOutOrderSummaryView> result = store.stockOutOrders(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP出库单列表查询完成 tenantId={} stockOutNo={} stockOutTypeCode={} warehouseId={} "
                        + "salesOrderNo={} transferOrderNo={} statusCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.stockOutNo()),
                ErpServiceValidation.value(criteria.stockOutTypeCode()), criteria.warehouseId(),
                ErpServiceValidation.value(criteria.salesOrderNo()),
                ErpServiceValidation.value(criteria.transferOrderNo()),
                ErpServiceValidation.value(criteria.statusCode()), result.items().size(), result.total());
        return result;
    }

    public InternalStockOutOrderDetailView stockOutOrder(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalStockOutOrderDetailView result = store.stockOutOrder(
                        tenantId, ErpServiceValidation.requireId(id, "出库单ID无效"))
                .orElseThrow(() -> notFound("出库单不存在"));
        log.debug("ERP出库单详情查询完成 tenantId={} stockOutOrderId={} stockOutNo={}",
                tenantId, result.id(), result.stockOutNo());
        return result;
    }

    public InternalStockOutOrderDetailView confirmSalesStockOut(InternalSalesStockOutCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesStockOutWrite normalized = normalize(tenantId, command);
        String stockOutNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_OUT_ORDER,
                candidate -> !store.existsByStockOutNo(tenantId, candidate));
        InternalStockOutOrderDetailView created = store.confirmSalesStockOut(
                tenantId, stockOutNo, normalized, actor.principalId().toString());
        log.info("ERP销售出库确认完成 tenantId={} stockOutOrderId={} stockOutNo={} salesOrderId={} "
                        + "salesOrderNo={} warehouseId={} totalQuantity={} actorId={}",
                tenantId, created.id(), created.stockOutNo(), created.salesOrderId(), created.salesOrderNo(),
                created.warehouseId(), created.totalQuantity(), actor.principalId());
        return created;
    }

    public InternalStockOutOrderDetailView confirmExternalStockOut(ExternalStockOutProjectionCommand command) {
        CallerIdentity actor = serviceActor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        if (command == null) throw badRequest("外部出库参数不能为空");
        String connectorId = requiredConnectorId(command.connectorId());
        String sourceSystemCode = ErpServiceValidation.code(command.sourceSystemCode(), "sourceSystemCode", true);
        String sourceDocumentNo = ErpServiceValidation.required(
                command.sourceDocumentNo(), "sourceDocumentNo不能为空", 128);
        InternalStockOutOrderDetailView existing = store.stockOutOrderBySource(
                tenantId, connectorId, sourceSystemCode, sourceDocumentNo).orElse(null);
        if (existing != null) {
            log.debug("ERP外部出库已存在，跳过重复确认 tenantId={} connectorId={} sourceSystem={} sourceDocumentNo={} "
                            + "stockOutOrderId={} stockOutNo={}",
                    tenantId, connectorId, sourceSystemCode, sourceDocumentNo,
                    existing.id(), existing.stockOutNo());
            return existing;
        }
        ExternalStockOutWrite normalized = normalizeExternal(
                tenantId, command, connectorId, sourceSystemCode, sourceDocumentNo);
        String stockOutNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_OUT_ORDER,
                normalized.stockOutTime(), candidate -> !store.existsByStockOutNo(tenantId, candidate));
        InternalStockOutOrderDetailView created = store.confirmExternalStockOut(
                tenantId, stockOutNo, normalized, actor.principalId().toString());
        log.info("ERP外部出库确认完成 tenantId={} connectorId={} sourceSystem={} sourceDocumentNo={} stockOutTypeCode={} "
                        + "stockOutOrderId={} stockOutNo={} warehouseId={} totalQuantity={} actorId={}",
                tenantId, normalized.connectorId(), normalized.sourceSystemCode(), normalized.sourceDocumentNo(),
                created.stockOutTypeCode(), created.id(), created.stockOutNo(), created.warehouseId(),
                created.totalQuantity(), actor.principalId());
        return created;
    }

    public InternalStockOutOrderDetailView confirmExternalGenericStockOut(
            ExternalGenericStockOutProjectionCommand command) {
        CallerIdentity actor = serviceActor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        if (command == null) throw badRequest("外部通用出库参数不能为空");
        String connectorId = requiredConnectorId(command.connectorId());
        String sourceSystemCode = ErpServiceValidation.code(command.sourceSystemCode(), "sourceSystemCode", true);
        String sourceDocumentNo = ErpServiceValidation.required(
                command.sourceDocumentNo(), "sourceDocumentNo不能为空", 128);
        InternalStockOutOrderDetailView existing = store.stockOutOrderBySource(
                tenantId, connectorId, sourceSystemCode, sourceDocumentNo).orElse(null);
        if (existing != null) {
            log.debug("ERP外部通用出库已存在，跳过重复确认 tenantId={} connectorId={} sourceSystem={} sourceDocumentNo={} "
                            + "stockOutOrderId={} stockOutNo={}",
                    tenantId, connectorId, sourceSystemCode, sourceDocumentNo,
                    existing.id(), existing.stockOutNo());
            return existing;
        }
        ExternalGenericStockOutWrite normalized = normalizeExternalGeneric(
                tenantId, command, connectorId, sourceSystemCode, sourceDocumentNo);
        String stockOutNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_OUT_ORDER,
                normalized.stockOutTime(), candidate -> !store.existsByStockOutNo(tenantId, candidate));
        InternalStockOutOrderDetailView created = store.confirmExternalGenericStockOut(
                tenantId, stockOutNo, normalized, actor.principalId().toString());
        log.info("ERP外部通用出库确认完成 tenantId={} connectorId={} sourceSystem={} sourceDocumentNo={} "
                        + "stockOutTypeCode={} stockOutOrderId={} stockOutNo={} warehouseId={} "
                        + "totalQuantity={} actorId={}",
                tenantId, normalized.connectorId(), normalized.sourceSystemCode(), normalized.sourceDocumentNo(),
                created.stockOutTypeCode(), created.id(), created.stockOutNo(), created.warehouseId(),
                created.totalQuantity(), actor.principalId());
        return created;
    }

    private SalesStockOutWrite normalize(String tenantId, InternalSalesStockOutCommand command) {
        if (command == null) throw badRequest("销售出库参数不能为空");
        Long salesOrderId = ErpServiceValidation.requireId(command.salesOrderId(), "salesOrderId无效");
        Long warehouseId = ErpServiceValidation.requireId(command.warehouseId(), "warehouseId无效");
        if (!store.warehouseActive(tenantId, warehouseId)) throw notFound("出库仓库不存在、已删除或已停用");
        if (store.existsActiveSalesStockOut(tenantId, salesOrderId)) throw conflict("销售订单已生成出库单，不能重复出库");
        List<SalesStockOutLineWrite> lines = lines(tenantId, command.lines());
        return new SalesStockOutWrite(salesOrderId,
                ErpServiceValidation.required(command.salesOrderNo(), "salesOrderNo不能为空", 50),
                warehouseId,
                ErpServiceValidation.optionalId(command.customerId(), "customerId"),
                ErpServiceValidation.text(command.customerNameSnapshot(), 200, "customerNameSnapshot"),
                ErpStockOutType.SALES.code(),
                ErpStockDocumentStatus.CONFIRMED.code(),
                command.stockOutTime() == null ? Instant.now() : command.stockOutTime(),
                lines,
                ErpServiceValidation.text(command.remark(), 1000, "remark"));
    }

    private ExternalStockOutWrite normalizeExternal(
            String tenantId, ExternalStockOutProjectionCommand command,
            String connectorId, String sourceSystemCode, String sourceDocumentNo) {
        String stockOutTypeCode = ErpServiceValidation.code(command.stockOutTypeCode(), "stockOutTypeCode", true);
        if (!ErpStockOutType.SALES.code().equals(stockOutTypeCode)) {
            throw badRequest("外部出库确认接口仅支持销售出库；调拨出库必须由调拨单确认出库生成");
        }
        Long warehouseId = ErpServiceValidation.requireId(command.warehouseId(), "warehouseId无效");
        if (!store.warehouseActive(tenantId, warehouseId)) throw notFound("出库仓库不存在、已删除或已停用");
        Long salesOrderId = ErpServiceValidation.optionalId(command.salesOrderId(), "salesOrderId");
        String salesOrderNo = ErpServiceValidation.text(command.salesOrderNo(), 50, "salesOrderNo");
        if (ErpStockOutType.SALES.code().equals(stockOutTypeCode)) {
            if (salesOrderId == null || salesOrderNo == null) {
                throw badRequest("销售出库必须携带salesOrderId和salesOrderNo");
            }
        }
        Instant stockOutTime = requiredSourceTime(command.stockOutTime(), "stockOutTime");
        List<ExternalStockOutLineWrite> lines = externalLines(tenantId, stockOutTypeCode, stockOutTime, command.lines());
        return new ExternalStockOutWrite(
                connectorId,
                sourceSystemCode,
                sourceDocumentNo,
                salesOrderId,
                salesOrderNo,
                null,
                null,
                warehouseId,
                ErpServiceValidation.optionalId(command.customerId(), "customerId"),
                ErpServiceValidation.text(command.customerNameSnapshot(), 200, "customerNameSnapshot"),
                stockOutTypeCode,
                ErpStockDocumentStatus.CONFIRMED.code(),
                stockOutTime,
                lines,
                command.shouldAffectStockBalance(),
                ErpServiceValidation.text(command.remark(), 1000, "remark"));
    }

    private ExternalGenericStockOutWrite normalizeExternalGeneric(
            String tenantId, ExternalGenericStockOutProjectionCommand command,
            String connectorId, String sourceSystemCode, String sourceDocumentNo) {
        String stockOutTypeCode = ErpServiceValidation.code(command.stockOutTypeCode(), "stockOutTypeCode", true);
        if (ErpStockOutType.SALES.code().equals(stockOutTypeCode)
                || ErpStockOutType.TRANSFER.code().equals(stockOutTypeCode)) {
            throw badRequest("外部通用出库不能处理SALES或TRANSFER");
        }
        if (!ErpStockOutType.PURCHASE_RETURN.code().equals(stockOutTypeCode)
                && !ErpStockOutType.INVENTORY_LOSS.code().equals(stockOutTypeCode)
                && !ErpStockOutType.OTHER.code().equals(stockOutTypeCode)
                && !ErpStockOutType.JOINT_OPERATION.code().equals(stockOutTypeCode)) {
            throw badRequest("外部通用出库类型不支持");
        }
        Long warehouseId = ErpServiceValidation.requireId(command.warehouseId(), "warehouseId无效");
        if (!store.warehouseActive(tenantId, warehouseId)) throw notFound("出库仓库不存在、已删除或已停用");
        Instant stockOutTime = requiredSourceTime(command.stockOutTime(), "stockOutTime");
        return new ExternalGenericStockOutWrite(connectorId, sourceSystemCode, sourceDocumentNo, warehouseId,
                stockOutTypeCode, ErpStockDocumentStatus.CONFIRMED.code(),
                stockOutTime,
                externalGenericLines(tenantId, stockOutTime, command.lines()),
                command.shouldAffectStockBalance(),
                ErpServiceValidation.text(command.remark(), 1000, "remark"));
    }

    private String requiredConnectorId(UUID connectorId) {
        if (connectorId == null) throw badRequest("connectorId不能为空");
        return connectorId.toString();
    }

    private List<SalesStockOutLineWrite> lines(
            String tenantId, List<InternalSalesStockOutLineCommand> source) {
        if (source == null || source.isEmpty()) throw badRequest("销售出库至少需要一条商品明细");
        if (source.size() > 200) throw badRequest("销售出库明细不能超过200条");
        Set<String> duplicateGuard = new LinkedHashSet<>();
        Set<String> flowNoGuard = new LinkedHashSet<>();
        java.util.ArrayList<SalesStockOutLineWrite> result = new java.util.ArrayList<>();
        for (InternalSalesStockOutLineCommand item : source) {
            if (item == null) continue;
            Long productId = ErpServiceValidation.requireId(item.productId(), "productId无效");
            Long variantId = ErpServiceValidation.requireId(item.productVariantId(), "productVariantId无效");
            String duplicateKey = productId + "::" + variantId;
            if (!duplicateGuard.add(duplicateKey)) throw badRequest("销售出库明细商品规格不能重复");
            String flowNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_FLOW,
                    candidate -> flowNoGuard.add(candidate) && !store.existsByFlowNo(tenantId, candidate));
            result.add(new SalesStockOutLineWrite(result.size() + 1,
                    ErpServiceValidation.optionalId(item.salesOrderLineId(), "salesOrderLineId"),
                    productId,
                    variantId,
                    ErpServiceValidation.text(item.productCodeSnapshot(), 50, "productCodeSnapshot"),
                    ErpServiceValidation.text(item.variantCodeSnapshot(), 50, "variantCodeSnapshot"),
                    ErpServiceValidation.required(item.productNameSnapshot(), "productNameSnapshot不能为空", 200),
                    ErpServiceValidation.code(item.unitCode(), "unitCode", true),
                    quantity(item.quantity(), "quantity"),
                    flowNo,
                    ErpServiceValidation.text(item.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) throw badRequest("销售出库至少需要一条有效商品明细");
        return List.copyOf(result);
    }

    private List<ExternalStockOutLineWrite> externalLines(
            String tenantId, String stockOutTypeCode, Instant stockOutTime,
            List<ExternalStockOutProjectionLineCommand> source) {
        if (source == null || source.isEmpty()) throw badRequest("外部出库至少需要一条商品明细");
        if (source.size() > 200) throw badRequest("外部出库明细不能超过200条");
        Set<String> duplicateGuard = new LinkedHashSet<>();
        Set<String> flowNoGuard = new LinkedHashSet<>();
        java.util.ArrayList<ExternalStockOutLineWrite> result = new java.util.ArrayList<>();
        for (ExternalStockOutProjectionLineCommand item : source) {
            if (item == null) continue;
            Long productId = ErpServiceValidation.requireId(item.productId(), "productId无效");
            Long variantId = ErpServiceValidation.requireId(item.productVariantId(), "productVariantId无效");
            String duplicateKey = productId + "::" + variantId;
            if (!duplicateGuard.add(duplicateKey)) throw badRequest("外部出库明细商品规格不能重复");
            Long salesOrderLineId = ErpServiceValidation.optionalId(item.salesOrderLineId(), "salesOrderLineId");
            Long transferOrderLineId = ErpServiceValidation.optionalId(item.transferOrderLineId(), "transferOrderLineId");
            if (ErpStockOutType.SALES.code().equals(stockOutTypeCode)) {
                transferOrderLineId = null;
            } else {
                salesOrderLineId = null;
            }
            String flowNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_FLOW,
                    stockOutTime,
                    candidate -> flowNoGuard.add(candidate) && !store.existsByFlowNo(tenantId, candidate));
            result.add(new ExternalStockOutLineWrite(result.size() + 1,
                    salesOrderLineId,
                    transferOrderLineId,
                    productId,
                    variantId,
                    ErpServiceValidation.text(item.productCodeSnapshot(), 50, "productCodeSnapshot"),
                    ErpServiceValidation.text(item.variantCodeSnapshot(), 50, "variantCodeSnapshot"),
                    ErpServiceValidation.required(item.productNameSnapshot(), "productNameSnapshot不能为空", 200),
                    ErpServiceValidation.code(item.unitCode(), "unitCode", true),
                    quantity(item.quantity(), "quantity"),
                    flowNo,
                    ErpServiceValidation.text(item.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) throw badRequest("外部出库至少需要一条有效商品明细");
        return List.copyOf(result);
    }

    private List<ExternalGenericStockOutLineWrite> externalGenericLines(
            String tenantId, Instant stockOutTime, List<ExternalGenericStockOutProjectionLineCommand> source) {
        if (source == null || source.isEmpty()) throw badRequest("外部通用出库至少需要一条商品明细");
        if (source.size() > 200) throw badRequest("外部通用出库明细不能超过200条");
        Set<String> duplicateGuard = new LinkedHashSet<>();
        Set<String> flowNoGuard = new LinkedHashSet<>();
        java.util.ArrayList<ExternalGenericStockOutLineWrite> result = new java.util.ArrayList<>();
        for (ExternalGenericStockOutProjectionLineCommand item : source) {
            if (item == null) continue;
            Long productId = ErpServiceValidation.requireId(item.productId(), "productId无效");
            Long variantId = ErpServiceValidation.requireId(item.productVariantId(), "productVariantId无效");
            String duplicateKey = productId + "::" + variantId;
            if (!duplicateGuard.add(duplicateKey)) throw badRequest("外部通用出库明细商品规格不能重复");
            ProductVariantSnapshot snapshot = store.productVariant(tenantId, productId, variantId)
                    .orElseThrow(() -> notFound("外部通用出库商品不存在、未提交或规格已删除"));
            String flowNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_FLOW,
                    stockOutTime,
                    candidate -> flowNoGuard.add(candidate) && !store.existsByFlowNo(tenantId, candidate));
            result.add(new ExternalGenericStockOutLineWrite(result.size() + 1,
                    productId,
                    variantId,
                    snapshot.productCode(),
                    snapshot.variantCode(),
                    snapshot.productName(),
                    snapshot.unitCode(),
                    quantity(item.quantity(), "quantity"),
                    flowNo,
                    ErpServiceValidation.text(item.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) throw badRequest("外部通用出库至少需要一条有效商品明细");
        return List.copyOf(result);
    }

    private static BigDecimal quantity(BigDecimal value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        if (value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        return value;
    }

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static CallerIdentity serviceActor(String permission) {
        CallerIdentity caller = actor(permission);
        if (!"SERVICE".equals(caller.principalScope())) {
            throw new AuthorizationDeniedException("service-caller");
        }
        return caller;
    }

    private static String tenant(String permission) {
        return actor(permission).tenantId().toString();
    }

    private static Instant requiredSourceTime(Instant value, String name) {
        if (value == null) throw badRequest("外部来源出库" + name + "必须使用来源业务时间");
        return value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
