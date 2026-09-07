package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.InternalProcurementStockInCommand;
import com.rigour.erp.api.v1.model.InternalProcurementStockInLineCommand;
import com.rigour.erp.api.v1.model.InternalStockInOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockInOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpStockInOrderStore;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementOrderLineSnapshot;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementOrderSnapshot;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementStockInLineWrite;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementStockInWrite;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.StockInOrderSearchCriteria;
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.erp.domain.enums.ErpProcurementStatus;
import com.rigour.erp.domain.enums.ErpStockDocumentStatus;
import com.rigour.erp.domain.enums.ErpStockInType;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** ERP 入库单用例；采购入库会同步生成入库单、库存余额和库存流水。 */
@Service
public final class ErpStockInOrderService {
    private static final Logger log = LoggerFactory.getLogger(ErpStockInOrderService.class);
    private static final String READ_PERMISSION = "erp:supply:read";
    private static final String WRITE_PERMISSION = "erp:supply:write";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ErpStockInOrderStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpStockInOrderService(ErpStockInOrderStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpStockInOrderService(ErpStockInOrderStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalStockInOrderSummaryView> stockInOrders(
            int begin, int step, String stockInNo, String stockInTypeCode, Long procurementOrderId,
            Long warehouseId, Long supplierId, String statusCode, Instant stockInTimeFrom, Instant stockInTimeTo) {
        String tenantId = tenant(READ_PERMISSION);
        if (stockInTimeFrom != null && stockInTimeTo != null && stockInTimeFrom.isAfter(stockInTimeTo)) {
            throw badRequest("stockInTimeFrom不能晚于stockInTimeTo");
        }
        StockInOrderSearchCriteria criteria = new StockInOrderSearchCriteria(
                ErpServiceValidation.text(stockInNo, 50, "stockInNo"),
                ErpServiceValidation.code(stockInTypeCode, "stockInTypeCode", false),
                ErpServiceValidation.optionalId(procurementOrderId, "procurementOrderId"),
                ErpServiceValidation.optionalId(warehouseId, "warehouseId"),
                ErpServiceValidation.optionalId(supplierId, "supplierId"),
                ErpServiceValidation.code(statusCode, "statusCode", false),
                stockInTimeFrom, stockInTimeTo);
        MasterDataPageView<InternalStockInOrderSummaryView> result = store.stockInOrders(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP入库单列表查询完成 tenantId={} stockInNo={} warehouseId={} supplierId={} statusCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.stockInNo()), criteria.warehouseId(),
                criteria.supplierId(), ErpServiceValidation.value(criteria.statusCode()),
                result.items().size(), result.total());
        return result;
    }

    public InternalStockInOrderDetailView stockInOrder(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalStockInOrderDetailView result = store.stockInOrder(
                        tenantId, ErpServiceValidation.requireId(id, "入库单ID无效"))
                .orElseThrow(() -> notFound("入库单不存在"));
        log.debug("ERP入库单详情查询完成 tenantId={} stockInOrderId={} stockInNo={}",
                tenantId, result.id(), result.stockInNo());
        return result;
    }

    public InternalStockInOrderDetailView confirmProcurementStockIn(InternalProcurementStockInCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        ProcurementStockInWrite normalized = normalize(tenantId, command);
        String stockInNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_IN_ORDER,
                candidate -> !store.existsByStockInNo(tenantId, candidate));
        InternalStockInOrderDetailView created = store.confirmProcurementStockIn(
                tenantId, stockInNo, normalized, actor.principalId().toString());
        log.info("ERP采购入库确认完成 tenantId={} stockInOrderId={} stockInNo={} procurementOrderId={} procurementNo={} "
                        + "warehouseId={} totalQuantity={} actorId={}",
                tenantId, created.id(), created.stockInNo(), created.procurementOrderId(), created.procurementNo(),
                created.warehouseId(), created.totalQuantity(), actor.principalId());
        return created;
    }

    private ProcurementStockInWrite normalize(String tenantId, InternalProcurementStockInCommand command) {
        if (command == null) throw badRequest("采购入库参数不能为空");
        Long procurementOrderId = ErpServiceValidation.requireId(
                command.procurementOrderId(), "procurementOrderId无效");
        if (command.procurementRevision() == null) throw badRequest("procurementRevision不能为空");
        ErpServiceValidation.requireRevision(command.procurementRevision());
        ProcurementOrderSnapshot order = store.procurementOrderForStockIn(tenantId, procurementOrderId)
                .orElseThrow(() -> notFound("采购订单不存在"));
        if (externalSource(order.sourceSystemCode())) {
            throw conflict("外部来源采购订单已按来源入库凭证同步，无需在ERP重复确认入库");
        }
        if (!ErpProcurementStatus.SUBMITTED.code().equals(order.statusCode())
                && !ErpProcurementStatus.PARTIAL_IN.code().equals(order.statusCode())) {
            throw conflict("只有已提交或部分入库的采购订单才能确认入库");
        }
        if (!Objects.equals(order.revision(), command.procurementRevision())) {
            throw conflict("采购订单已被其他人修改，请刷新后重试");
        }
        Map<Long, ProcurementOrderLineSnapshot> availableLines = new LinkedHashMap<>();
        for (ProcurementOrderLineSnapshot line : order.lines()) {
            availableLines.put(line.id(), line);
        }
        List<ProcurementStockInLineWrite> lines = lines(tenantId, command.lines(), availableLines);
        String nextStatus = nextProcurementStatus(order.lines(), lines);
        return new ProcurementStockInWrite(order.id(), command.procurementRevision(),
                ErpStockInType.PURCHASE.code(), ErpStockDocumentStatus.CONFIRMED.code(), nextStatus,
                command.stockInTime() == null ? Instant.now() : command.stockInTime(),
                order.targetWarehouseId(), order.supplierId(), order.procurementNo(), lines,
                ErpServiceValidation.text(command.remark(), 1000, "remark"));
    }

    private List<ProcurementStockInLineWrite> lines(
            String tenantId, List<InternalProcurementStockInLineCommand> source,
            Map<Long, ProcurementOrderLineSnapshot> availableLines) {
        if (source == null || source.isEmpty()) throw badRequest("采购入库至少需要一条明细");
        if (source.size() > 200) throw badRequest("采购入库明细不能超过200条");
        Map<Long, BigDecimal> duplicateGuard = new LinkedHashMap<>();
        Set<String> flowNoGuard = new LinkedHashSet<>();
        java.util.ArrayList<ProcurementStockInLineWrite> result = new java.util.ArrayList<>();
        for (InternalProcurementStockInLineCommand item : source) {
            if (item == null) continue;
            Long procurementLineId = ErpServiceValidation.requireId(
                    item.procurementOrderLineId(), "procurementOrderLineId无效");
            if (duplicateGuard.containsKey(procurementLineId)) {
                throw badRequest("采购入库明细不能重复选择同一条采购明细");
            }
            ProcurementOrderLineSnapshot procurementLine = availableLines.get(procurementLineId);
            if (procurementLine == null) throw badRequest("采购入库明细不属于当前采购订单");
            BigDecimal quantity = quantity(item.quantity(), "quantity");
            BigDecimal received = zeroIfNull(procurementLine.receivedQuantity());
            BigDecimal remaining = procurementLine.quantity().subtract(received);
            if (quantity.compareTo(remaining) > 0) {
                throw conflict("采购入库数量不能超过未入库数量");
            }
            duplicateGuard.put(procurementLineId, quantity);
            String flowNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_FLOW,
                    candidate -> flowNoGuard.add(candidate) && !store.existsByFlowNo(tenantId, candidate));
            BigDecimal amount = quantity.multiply(zeroIfNull(procurementLine.unitPrice()));
            result.add(new ProcurementStockInLineWrite(result.size() + 1, procurementLine.id(),
                    procurementLine.productId(), procurementLine.productVariantId(), procurementLine.productCode(),
                    procurementLine.variantCode(), procurementLine.productName(), procurementLine.unitCode(),
                    quantity, zeroIfNull(procurementLine.unitPrice()), amount, flowNo,
                    ErpServiceValidation.text(item.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) throw badRequest("采购入库至少需要一条有效明细");
        return List.copyOf(result);
    }

    private static String nextProcurementStatus(
            List<ProcurementOrderLineSnapshot> current, List<ProcurementStockInLineWrite> stockInLines) {
        Map<Long, BigDecimal> stockInByProcurementLine = new LinkedHashMap<>();
        for (ProcurementStockInLineWrite line : stockInLines) {
            stockInByProcurementLine.merge(line.procurementOrderLineId(), line.quantity(), BigDecimal::add);
        }
        boolean completed = true;
        for (ProcurementOrderLineSnapshot line : current) {
            BigDecimal afterReceived = zeroIfNull(line.receivedQuantity())
                    .add(stockInByProcurementLine.getOrDefault(line.id(), ZERO));
            if (afterReceived.compareTo(line.quantity()) < 0) {
                completed = false;
                break;
            }
        }
        return completed ? ErpProcurementStatus.COMPLETED.code() : ErpProcurementStatus.PARTIAL_IN.code();
    }

    private static BigDecimal quantity(BigDecimal value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        if (value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        return value;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static boolean externalSource(String sourceSystemCode) {
        return sourceSystemCode != null && !sourceSystemCode.isBlank();
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

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
