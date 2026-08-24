package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderLineCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderSummaryView;
import com.rigour.erp.api.v1.model.InternalTransferStockInCommand;
import com.rigour.erp.api.v1.model.InternalTransferStockOutCommand;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpTransferOrderStore;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.ExternalTransferStockOutLineWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.ExternalTransferStockOutWrite;
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
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.erp.domain.enums.ErpStockDocumentStatus;
import com.rigour.erp.domain.enums.ErpStockInType;
import com.rigour.erp.domain.enums.ErpStockOutType;
import com.rigour.erp.domain.enums.ErpTransferStatus;
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

/** ERP 调拨单用例；确认调拨出入库时生成统一出入库单并写库存。 */
@Service
public final class ErpTransferOrderService {
    private static final Logger log = LoggerFactory.getLogger(ErpTransferOrderService.class);
    private static final String READ_PERMISSION = "erp:supply:read";
    private static final String WRITE_PERMISSION = "erp:supply:write";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ErpTransferOrderStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpTransferOrderService(ErpTransferOrderStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpTransferOrderService(ErpTransferOrderStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<InternalTransferOrderSummaryView> transferOrders(
            int begin, int step, String transferNo, Long sourceWarehouseId, Long targetWarehouseId,
            String statusCode, Instant stockOutTimeFrom, Instant stockOutTimeTo) {
        String tenantId = tenant(READ_PERMISSION);
        if (stockOutTimeFrom != null && stockOutTimeTo != null && stockOutTimeFrom.isAfter(stockOutTimeTo)) {
            throw badRequest("stockOutTimeFrom不能晚于stockOutTimeTo");
        }
        TransferOrderSearchCriteria criteria = new TransferOrderSearchCriteria(
                ErpServiceValidation.text(transferNo, 50, "transferNo"),
                ErpServiceValidation.optionalId(sourceWarehouseId, "sourceWarehouseId"),
                ErpServiceValidation.optionalId(targetWarehouseId, "targetWarehouseId"),
                ErpServiceValidation.code(statusCode, "statusCode", false),
                stockOutTimeFrom, stockOutTimeTo);
        MasterDataPageView<InternalTransferOrderSummaryView> result = store.transferOrders(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP调拨单列表查询完成 tenantId={} transferNo={} sourceWarehouseId={} targetWarehouseId={} statusCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.transferNo()), criteria.sourceWarehouseId(),
                criteria.targetWarehouseId(), ErpServiceValidation.value(criteria.statusCode()),
                result.items().size(), result.total());
        return result;
    }

    public InternalTransferOrderDetailView transferOrder(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        InternalTransferOrderDetailView result = store.transferOrder(
                        tenantId, ErpServiceValidation.requireId(id, "调拨单ID无效"))
                .orElseThrow(() -> notFound("调拨单不存在"));
        log.debug("ERP调拨单详情查询完成 tenantId={} transferOrderId={} transferNo={}",
                tenantId, result.id(), result.transferNo());
        return result;
    }

    public InternalTransferOrderDetailView create(InternalTransferOrderCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        TransferOrderWrite normalized = normalize(tenantId, command, false);
        String transferNo = codeGenerator.generateUnique(ErpBusinessCodeRules.TRANSFER_ORDER,
                candidate -> !store.existsByTransferNo(tenantId, candidate));
        InternalTransferOrderDetailView created = store.create(
                tenantId, transferNo, normalized, actor.principalId().toString());
        log.info("ERP调拨单创建完成 tenantId={} transferOrderId={} transferNo={} sourceWarehouseId={} "
                        + "targetWarehouseId={} totalQuantity={} actorId={}",
                tenantId, created.id(), created.transferNo(), created.sourceWarehouseId(),
                created.targetWarehouseId(), created.totalQuantity(), actor.principalId());
        return created;
    }

    public InternalTransferOrderDetailView update(Long id, InternalTransferOrderCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        Long transferOrderId = ErpServiceValidation.requireId(id, "调拨单ID无效");
        TransferOrderWrite normalized = normalize(tenantId, command, true);
        InternalTransferOrderDetailView updated = store.update(
                tenantId, transferOrderId, normalized, actor.principalId().toString());
        log.info("ERP调拨单修改完成 tenantId={} transferOrderId={} transferNo={} revision={} actorId={}",
                tenantId, updated.id(), updated.transferNo(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        ErpServiceValidation.requireRevision(revision);
        Long transferOrderId = ErpServiceValidation.requireId(id, "调拨单ID无效");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, transferOrderId, revision, actor.principalId().toString());
        log.info("ERP调拨单逻辑删除完成 tenantId={} transferOrderId={} revision={} actorId={}",
                tenantId, transferOrderId, revision, actor.principalId());
    }

    public InternalTransferOrderDetailView confirmStockOut(Long id, InternalTransferStockOutCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        TransferStockOutWrite normalized = normalizeStockOut(
                tenantId, ErpServiceValidation.requireId(id, "调拨单ID无效"), command);
        String stockOutNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_OUT_ORDER,
                candidate -> !store.existsByStockOutNo(tenantId, candidate));
        InternalTransferOrderDetailView confirmed = store.confirmStockOut(
                tenantId, stockOutNo, normalized, actor.principalId().toString());
        log.info("ERP调拨出库确认完成 tenantId={} transferOrderId={} transferNo={} stockOutNo={} "
                        + "sourceWarehouseId={} totalQuantity={} actorId={}",
                tenantId, confirmed.id(), confirmed.transferNo(), confirmed.stockOutNo(),
                confirmed.sourceWarehouseId(), confirmed.totalQuantity(), actor.principalId());
        return confirmed;
    }

    public InternalTransferOrderDetailView confirmExternalStockOut(
            ExternalTransferStockOutProjectionCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        if (command == null) throw badRequest("外部调拨出库参数不能为空");
        String sourceSystemCode = ErpServiceValidation.code(command.sourceSystemCode(), "sourceSystemCode", true);
        String sourceDocumentNo = ErpServiceValidation.required(
                command.sourceDocumentNo(), "sourceDocumentNo不能为空", 128);
        InternalTransferOrderDetailView existing = store.transferOrderBySource(
                tenantId, sourceSystemCode, sourceDocumentNo).orElse(null);
        if (existing != null) {
            log.debug("ERP外部调拨出库已存在，跳过重复确认 tenantId={} sourceSystem={} sourceDocumentNo={} "
                            + "transferOrderId={} transferNo={} stockOutNo={}",
                    tenantId, sourceSystemCode, sourceDocumentNo,
                    existing.id(), existing.transferNo(), existing.stockOutNo());
            return existing;
        }
        ExternalTransferStockOutWrite normalized = normalizeExternalStockOut(
                tenantId, command, sourceSystemCode, sourceDocumentNo);
        String transferNo = codeGenerator.generateUnique(ErpBusinessCodeRules.TRANSFER_ORDER,
                normalized.stockOutTime(), candidate -> !store.existsByTransferNo(tenantId, candidate));
        String stockOutNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_OUT_ORDER,
                normalized.stockOutTime(), candidate -> !store.existsByStockOutNo(tenantId, candidate));
        InternalTransferOrderDetailView confirmed = store.confirmExternalStockOut(
                tenantId, transferNo, stockOutNo, normalized, actor.principalId().toString());
        log.info("ERP外部调拨出库确认完成 tenantId={} sourceSystem={} sourceDocumentNo={} "
                        + "transferOrderId={} transferNo={} stockOutNo={} sourceWarehouseId={} "
                        + "targetWarehouseId={} totalQuantity={} actorId={}",
                tenantId, sourceSystemCode, sourceDocumentNo, confirmed.id(), confirmed.transferNo(),
                confirmed.stockOutNo(), confirmed.sourceWarehouseId(), confirmed.targetWarehouseId(),
                confirmed.totalQuantity(), actor.principalId());
        return confirmed;
    }

    public InternalTransferOrderDetailView confirmStockIn(Long id, InternalTransferStockInCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        TransferStockInWrite normalized = normalizeStockIn(
                tenantId, ErpServiceValidation.requireId(id, "调拨单ID无效"), command);
        String stockInNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_IN_ORDER,
                candidate -> !store.existsByStockInNo(tenantId, candidate));
        InternalTransferOrderDetailView confirmed = store.confirmStockIn(
                tenantId, stockInNo, normalized, actor.principalId().toString());
        log.info("ERP调拨入库确认完成 tenantId={} transferOrderId={} transferNo={} stockInNo={} "
                        + "targetWarehouseId={} totalQuantity={} actorId={}",
                tenantId, confirmed.id(), confirmed.transferNo(), confirmed.stockInNo(),
                confirmed.targetWarehouseId(), confirmed.totalQuantity(), actor.principalId());
        return confirmed;
    }

    private TransferOrderWrite normalize(String tenantId, InternalTransferOrderCommand command, boolean update) {
        if (command == null) throw badRequest("调拨单参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        Long sourceWarehouseId = ErpServiceValidation.requireId(command.sourceWarehouseId(), "sourceWarehouseId无效");
        Long targetWarehouseId = ErpServiceValidation.requireId(command.targetWarehouseId(), "targetWarehouseId无效");
        if (Objects.equals(sourceWarehouseId, targetWarehouseId)) {
            throw badRequest("来源仓库和目标仓库不能相同");
        }
        if (!store.warehouseActive(tenantId, sourceWarehouseId)) throw notFound("来源仓库不存在、已删除或已停用");
        if (!store.warehouseActive(tenantId, targetWarehouseId)) throw notFound("目标仓库不存在、已删除或已停用");
        List<TransferOrderLineWrite> lines = lines(tenantId, command.lines());
        return new TransferOrderWrite(null, null, sourceWarehouseId, targetWarehouseId,
                ErpTransferStatus.DRAFT.code(), lines,
                ErpServiceValidation.text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
    }

    private List<TransferOrderLineWrite> lines(
            String tenantId, List<InternalTransferOrderLineCommand> source) {
        if (source == null || source.isEmpty()) throw badRequest("调拨单至少需要一条商品明细");
        if (source.size() > 200) throw badRequest("调拨单明细不能超过200条");
        Set<String> duplicateGuard = new LinkedHashSet<>();
        java.util.ArrayList<TransferOrderLineWrite> result = new java.util.ArrayList<>();
        for (InternalTransferOrderLineCommand item : source) {
            if (item == null) continue;
            Long productId = ErpServiceValidation.requireId(item.productId(), "productId无效");
            Long variantId = ErpServiceValidation.requireId(item.productVariantId(), "productVariantId无效");
            String duplicateKey = productId + "::" + variantId;
            if (!duplicateGuard.add(duplicateKey)) throw badRequest("调拨单明细商品规格不能重复");
            ProductVariantSnapshot snapshot = store.productVariant(tenantId, productId, variantId)
                    .orElseThrow(() -> notFound("调拨商品不存在、未提交或规格已删除"));
            result.add(new TransferOrderLineWrite(result.size() + 1, productId, variantId,
                    snapshot.productCode(), snapshot.variantCode(), snapshot.productName(), snapshot.unitCode(),
                    quantity(item.quantity(), "quantity"),
                    ErpServiceValidation.text(item.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) throw badRequest("调拨单至少需要一条有效商品明细");
        return List.copyOf(result);
    }

    private TransferStockOutWrite normalizeStockOut(
            String tenantId, Long transferOrderId, InternalTransferStockOutCommand command) {
        if (command == null) throw badRequest("调拨出库参数不能为空");
        if (command.revision() == null) throw badRequest("revision不能为空");
        ErpServiceValidation.requireRevision(command.revision());
        TransferOrderSnapshot transfer = store.transferOrderForStockOut(tenantId, transferOrderId)
                .orElseThrow(() -> notFound("调拨单不存在"));
        if (!ErpTransferStatus.DRAFT.code().equals(transfer.statusCode())) {
            throw conflict("只有草稿调拨单才能确认调拨出库");
        }
        if (!Objects.equals(transfer.revision(), command.revision())) {
            throw conflict("调拨单已被其他人修改，请刷新后重试");
        }
        Set<String> flowNoGuard = new LinkedHashSet<>();
        List<TransferStockOutLineWrite> lines = transfer.lines().stream()
                .map(line -> stockOutLine(tenantId, flowNoGuard, line))
                .toList();
        if (lines.isEmpty()) throw badRequest("调拨单至少需要一条有效商品明细");
        return new TransferStockOutWrite(transfer.id(), command.revision(), transfer.transferNo(), null, null,
                ErpStockOutType.TRANSFER.code(), ErpStockDocumentStatus.CONFIRMED.code(),
                ErpTransferStatus.OUT_CONFIRMED.code(),
                command.stockOutTime() == null ? Instant.now() : command.stockOutTime(),
                transfer.sourceWarehouseId(), lines, ErpServiceValidation.text(command.remark(), 1000, "remark"));
    }

    private TransferStockInWrite normalizeStockIn(
            String tenantId, Long transferOrderId, InternalTransferStockInCommand command) {
        if (command == null) throw badRequest("调拨入库参数不能为空");
        if (command.revision() == null) throw badRequest("revision不能为空");
        ErpServiceValidation.requireRevision(command.revision());
        TransferOrderSnapshot transfer = store.transferOrderForStockIn(tenantId, transferOrderId)
                .orElseThrow(() -> notFound("调拨单不存在"));
        if (!ErpTransferStatus.OUT_CONFIRMED.code().equals(transfer.statusCode())) {
            throw conflict("只有已确认出库的调拨单才能确认调拨入库");
        }
        if (!Objects.equals(transfer.revision(), command.revision())) {
            throw conflict("调拨单已被其他人修改，请刷新后重试");
        }
        Set<String> flowNoGuard = new LinkedHashSet<>();
        List<TransferStockInLineWrite> lines = transfer.lines().stream()
                .map(line -> stockInLine(tenantId, flowNoGuard, line))
                .toList();
        if (lines.isEmpty()) throw badRequest("调拨单至少需要一条有效商品明细");
        return new TransferStockInWrite(transfer.id(), command.revision(), transfer.transferNo(),
                ErpStockInType.TRANSFER.code(), ErpStockDocumentStatus.CONFIRMED.code(),
                ErpTransferStatus.IN_CONFIRMED.code(),
                command.stockInTime() == null ? Instant.now() : command.stockInTime(),
                transfer.targetWarehouseId(), lines, ErpServiceValidation.text(command.remark(), 1000, "remark"));
    }

    private ExternalTransferStockOutWrite normalizeExternalStockOut(
            String tenantId, ExternalTransferStockOutProjectionCommand command,
            String sourceSystemCode, String sourceDocumentNo) {
        Long sourceWarehouseId = ErpServiceValidation.requireId(command.sourceWarehouseId(), "sourceWarehouseId无效");
        Long targetWarehouseId = ErpServiceValidation.requireId(command.targetWarehouseId(), "targetWarehouseId无效");
        if (Objects.equals(sourceWarehouseId, targetWarehouseId)) {
            throw badRequest("来源仓库和目标仓库不能相同");
        }
        if (!store.warehouseActive(tenantId, sourceWarehouseId)) throw notFound("来源仓库不存在、已删除或已停用");
        if (!store.warehouseActive(tenantId, targetWarehouseId)) throw notFound("目标仓库不存在、已删除或已停用");
        List<ExternalTransferStockOutLineWrite> lines = externalStockOutLines(tenantId, command.lines());
        return new ExternalTransferStockOutWrite(sourceSystemCode, sourceDocumentNo,
                sourceWarehouseId, targetWarehouseId,
                command.stockOutTime() == null ? Instant.now() : command.stockOutTime(),
                lines, ErpServiceValidation.text(command.remark(), 1000, "remark"));
    }

    private List<ExternalTransferStockOutLineWrite> externalStockOutLines(
            String tenantId, List<ExternalTransferStockOutProjectionLineCommand> source) {
        if (source == null || source.isEmpty()) throw badRequest("外部调拨出库至少需要一条商品明细");
        if (source.size() > 200) throw badRequest("外部调拨出库明细不能超过200条");
        Set<String> duplicateGuard = new LinkedHashSet<>();
        Set<String> flowNoGuard = new LinkedHashSet<>();
        java.util.ArrayList<ExternalTransferStockOutLineWrite> result = new java.util.ArrayList<>();
        for (ExternalTransferStockOutProjectionLineCommand item : source) {
            if (item == null) continue;
            Long productId = ErpServiceValidation.requireId(item.productId(), "productId无效");
            Long variantId = ErpServiceValidation.requireId(item.productVariantId(), "productVariantId无效");
            String duplicateKey = productId + "::" + variantId;
            if (!duplicateGuard.add(duplicateKey)) throw badRequest("外部调拨出库明细商品规格不能重复");
            ProductVariantSnapshot snapshot = store.productVariant(tenantId, productId, variantId)
                    .orElseThrow(() -> notFound("外部调拨出库商品不存在、未提交或规格已删除"));
            String flowNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_FLOW,
                    candidate -> flowNoGuard.add(candidate) && !store.existsByFlowNo(tenantId, candidate));
            result.add(new ExternalTransferStockOutLineWrite(result.size() + 1,
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
        if (result.isEmpty()) throw badRequest("外部调拨出库至少需要一条有效商品明细");
        return List.copyOf(result);
    }

    private TransferStockOutLineWrite stockOutLine(
            String tenantId, Set<String> flowNoGuard, TransferOrderLineSnapshot line) {
        String flowNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_FLOW,
                candidate -> flowNoGuard.add(candidate) && !store.existsByFlowNo(tenantId, candidate));
        return new TransferStockOutLineWrite(line.lineNo(), line.id(), line.productId(), line.productVariantId(),
                line.productCode(), line.variantCode(), line.productName(), line.unitCode(), line.quantity(), flowNo,
                line.remark());
    }

    private TransferStockInLineWrite stockInLine(
            String tenantId, Set<String> flowNoGuard, TransferOrderLineSnapshot line) {
        String flowNo = codeGenerator.generateUnique(ErpBusinessCodeRules.STOCK_FLOW,
                candidate -> flowNoGuard.add(candidate) && !store.existsByFlowNo(tenantId, candidate));
        return new TransferStockInLineWrite(line.lineNo(), line.id(), line.productId(), line.productVariantId(),
                line.productCode(), line.variantCode(), line.productName(), line.unitCode(), line.quantity(), flowNo,
                line.remark());
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
