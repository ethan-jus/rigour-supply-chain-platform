package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.TransportFeeImportModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import java.util.*;

/** 缺失运费补录：精确匹配、逐笔短事务，原文件归档为费用凭证。 */
@Service
public class TransportFeeImportService {
    static final String PERMISSION = "order:logistics-cost:write";
    static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final OrderLogisticsCostStore costs;
    private final PlatformTransportStore platform;
    private final ShipmentTransportStore shipments;
    private final CostAttachmentStorage storage;
    private final CostAttachmentStore files;
    private final CostAttachmentService evidence;
    private final TransactionTemplate tx;
    private final TransportFeeArchiveStore archives;

    public TransportFeeImportService(
            OrderLogisticsCostStore costs,
            PlatformTransportStore platform,
            ShipmentTransportStore shipments,
            CostAttachmentStorage storage,
            CostAttachmentStore files,
            CostAttachmentService evidence,
            PlatformTransactionManager manager,
            TransportFeeArchiveStore archives) {
        this.costs = costs;
        this.platform = platform;
        this.shipments = shipments;
        this.storage = storage;
        this.files = files;
        this.evidence = evidence;
        this.archives = archives;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private String tenant() {
        AuthorizationContext.requirePermission(PERMISSION);
        var c = AuthorizationContext.requireCurrent();
        if (c.tenantId() == null) throw new AuthorizationDeniedException(PERMISSION);
        return c.tenantId().toString();
    }

    public Preview preview(byte[] bytes, String carrier) {
        return new Preview(hash(bytes), evaluate(TransportFeeWorkbookReader.read(bytes), carrier));
    }

    private List<Item> evaluate(List<TransportFeeWorkbookReader.Row> rows, String carrier) {
        String tenant = tenant();
        if (carrier != null && carrier.length() > 64) throw bad("物流公司无效");
        var numbers =
                rows.stream()
                        .filter(r -> r.problem() == null)
                        .map(TransportFeeWorkbookReader.Row::trackingNo)
                        .distinct()
                        .toList();
        var candidates = costs.trackingCandidates(tenant, numbers, carrier, PERMISSION);
        var ids = candidates.stream().map(LogisticsCostDetailView::id).toList();
        var platforms = platform.byCosts(tenant, ids);
        var sources = shipments.byCosts(tenant, ids);
        var matches = new HashMap<String, List<LogisticsCostDetailView>>();
        for (var c : candidates) {
            var keys = new HashSet<String>();
            if (c.data().ledger() != null) {
                keys.add(c.data().ledger().platformOrderNo());
                keys.add(c.data().ledger().trackingNo());
            }
            if (c.data().logistics() != null) keys.add(c.data().logistics().trackingNo());
            var p = platforms.get(c.id());
            if (p != null)
                keys.add(
                        (p.source().latestOrder() == null
                                        ? p.source().order()
                                        : p.source().latestOrder())
                                .orderNo());
            for (var s : sources.getOrDefault(c.id(), List.of()))
                keys.addAll(s.latest().trackingNumbers());
            for (var no : keys)
                if (no != null && numbers.contains(no))
                    matches.computeIfAbsent(no, n -> new ArrayList<>()).add(c);
        }
        var count = new HashMap<String, Integer>();
        rows.forEach(r -> count.merge(r.trackingNo(), 1, Integer::sum));
        var items = new ArrayList<Item>();
        for (var row : rows) {
            var found = matches.getOrDefault(row.trackingNo(), List.of());
            String message = row.problem();
            String state = "ERROR";
            LogisticsCostDetailView cost = found.size() == 1 ? found.getFirst() : null;
            if (message == null && count.get(row.trackingNo()) > 1) message = "文件中快递单号重复，请合并核对";
            if (message == null && found.isEmpty()) message = "未找到可编辑的运输记录，请核对单号或物流公司";
            if (message == null && found.size() > 1) message = "同一单号匹配多笔运输，请指定物流公司或逐笔核对";
            if (message == null && cost.data().amount() != null) {
                state =
                        cost.data().amount().compareTo(row.amount()) == 0
                                ? "UNCHANGED"
                                : "CONFLICT";
                message = state.equals("UNCHANGED") ? "已有相同运费，无需更新" : "已有运费，本次不覆盖，请打开运输记录核对";
            }
            if (message == null
                    && (cost.data().ledger() == null
                            || TransportAllocationPlan.complex(cost.data().ledger())
                            || cost.data().ledger().sources() == null
                            || cost.data().ledger().sources().stream()
                                    .anyMatch(
                                            s ->
                                                    s.allocations() == null
                                                            || s.allocations().size() != 1)
                            || cost.data().ledger().feeItems().stream()
                                    .anyMatch(f -> "MANUAL".equals(f.allocationMethodCode()))))
                message = "已有特殊费用或分摊，请在运输记录中编辑";
            if (message == null) {
                state = "READY";
                message = "可补录运费";
            }
            items.add(item(row, cost, state, message));
        }
        var targetCounts = new HashMap<String, Integer>();
        items.stream()
                .filter(i -> "READY".equals(i.state()))
                .forEach(i -> targetCounts.merge(i.costId(), 1, Integer::sum));
        return items.stream()
                .map(
                        i ->
                                "READY".equals(i.state()) && targetCounts.get(i.costId()) > 1
                                        ? new Item(
                                                i.line(),
                                                i.trackingNo(),
                                                i.amount(),
                                                i.remark(),
                                                i.costId(),
                                                i.documentNo(),
                                                i.revision(),
                                                "ERROR",
                                                "多个快递单号属于同一运输，请合并为一笔运费")
                                        : i)
                .toList();
    }

    public Preview apply(String name, byte[] bytes, Apply command) {
        String tenant = tenant();
        if (command == null
                || !Objects.equals(hash(bytes), command.fileHash())
                || command.selections() == null
                || command.selections().isEmpty()
                || command.selections().size() > 500) throw bad("请先核对文件并选择可补录的明细");
        CostAttachmentService.validateEvidence(name, MIME, bytes);
        var sourceRows = TransportFeeWorkbookReader.read(bytes);
        var sourceByLine = new HashMap<Integer, TransportFeeWorkbookReader.Row>();
        sourceRows.forEach(r -> sourceByLine.put(r.line(), r));
        var preview = new Preview(hash(bytes), evaluate(sourceRows, command.carrierCode()));
        var byLine = new HashMap<Integer, Item>();
        preview.items().forEach(i -> byLine.put(i.line(), i));
        var selected = new HashSet<Integer>();
        for (var s : command.selections())
            if (s == null || !selected.add(s.line()) || !byLine.containsKey(s.line()))
                throw bad("导入行号无效或重复");
        TransportFeeArchiveStore.Archive archive = null;
        var results = new ArrayList<Item>();
        for (var selection : command.selections()) {
            var i = byLine.get(selection.line());
            if (!Objects.equals(selection.costId(), i.costId())) {
                results.add(state(i, "CONFLICT", "匹配记录已变化，请重新核对"));
                continue;
            }
            if (!"READY".equals(i.state())) {
                results.add(i);
                continue;
            }
            if (selection.revision() != i.revision()) {
                results.add(state(i, "CONFLICT", "记录已修改，请重新核对"));
                continue;
            }
            if (archive == null) archive = archive(tenant, name, bytes, preview.fileHash());
            var evidenceBytes =
                    TransportFeeWorkbookReader.evidence(sourceByLine.get(i.line()), name);
            String evidenceName = "运费补录-第" + i.line() + "行.xlsx";
            String archived;
            try {
                archived = storage.upload(
                            tenant,
                            "fee_import_" + archive.id() + "_" + i.costId(),
                            MIME,
                            evidenceBytes);
            } catch (RuntimeException failed) {
                results.add(state(i, "ERROR", "本笔凭证保存失败，运费未更新，请重试"));
                continue;
            }
            var completion = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
            try {
                var result =
                        tx.execute(
                                status -> {
                                    TransactionSynchronizationManager.registerSynchronization(
                                            new TransactionSynchronization() {
                                                @Override
                                                public void afterCompletion(int value) {
                                                    completion.set(value);
                                                }
                                            });
                                    files.lock(tenant);
                                    // 在同一租户锁下重新匹配，防止预览之后另一笔物流占用同一单号。
                                    var check =
                                            evaluate(
                                                            List.of(sourceByLine.get(i.line())),
                                                            command.carrierCode())
                                                    .getFirst();
                                    if (!"READY".equals(check.state())
                                            || !Objects.equals(check.costId(), i.costId()))
                                        return check;
                                    var old =
                                            costs.entry(
                                                    tenant, "LOGISTICS", i.costId(), PERMISSION);
                                    if (old.revision() != selection.revision())
                                        return state(i, "CONFLICT", "记录已修改，请重新核对");
                                    var value =
                                            TransportAllocationPlan.reprice(old.data(), i.amount());
                                    var fees =
                                            value.ledger().feeItems().stream()
                                                    .map(
                                                            f ->
                                                                    new LogisticsCostLedger.Fee(
                                                                            f.feeKey(),
                                                                            f.feeTypeCode(),
                                                                            f.feeAmount(),
                                                                            f.bearerCode(),
                                                                            f.ourAmount(),
                                                                            f
                                                                                    .allocationMethodCode(),
                                                                            i.amount().signum() == 0
                                                                                    ? i.remark()
                                                                                    : f
                                                                                            .zeroCostReason(),
                                                                            f.remark(),
                                                                            f.basisUnitCode()))
                                                    .toList();
                                    value =
                                            new LogisticsCostCommand(
                                                    value.requestId(),
                                                    old.revision(),
                                                    value.title(),
                                                    value.typeCode(),
                                                    value.cityCode(),
                                                    value.cityName(),
                                                    value.departmentId(),
                                                    value.ownerEmployeeCode(),
                                                    value.costDate(),
                                                    value.amount(),
                                                    value.paymentStatusCode(),
                                                    value.logistics(),
                                                    value.remark(),
                                                    "Excel补录运费，文件第"
                                                            + i.line()
                                                            + "行"
                                                            + (i.remark().isBlank()
                                                                    ? ""
                                                                    : "：" + i.remark()),
                                                    value.ledger().withFeeItems(fees));
                                    var saved =
                                            costs.save(
                                                    tenant,
                                                    "LOGISTICS",
                                                    old.id(),
                                                    value,
                                                    old.sources(),
                                                    old.warehouseName(),
                                                    old.ownerEmployeeName(),
                                                    AuthorizationContext.requireCurrent()
                                                            .principalId()
                                                            .toString(),
                                                    PERMISSION);
                                    evidence.attachUploaded(
                                            saved.id(),
                                            saved.revision(),
                                            null,
                                            "EXPENSE_VOUCHER",
                                            evidenceName,
                                            MIME,
                                            evidenceBytes.length,
                                            archived);
                                    return state(i, "IMPORTED", "运费已补录");
                                });
                results.add(result);
            } catch (BusinessException e) {
                results.add(state(i, "ERROR", e.getMessage()));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("运费导入处理异常 line={} cost={}", i.line(), i.costId(), e);
                results.add(state(i, "ERROR", "本笔未完成，请刷新核对后重试"));
            } finally {
                if (completion.get() == TransactionSynchronization.STATUS_ROLLED_BACK)
                    storage.discard(archived);
                // 已提交但未写入成本（并发时变成无需更新）也没有元数据引用新对象。
                else if (completion.get() == TransactionSynchronization.STATUS_COMMITTED
                        && !"IMPORTED".equals(results.getLast().state())) storage.discard(archived);
            }
        }
        return new Preview(preview.fileHash(), List.copyOf(results));
    }

    private TransportFeeArchiveStore.Archive archive(
            String tenant, String name, byte[] bytes, String hash) {
        String actor = AuthorizationContext.requireCurrent().principalId().toString();
        var old = archives.find(tenant, actor, hash);
        if (old != null) return old;
        String key =
                storage.upload(tenant, "fee_import_original_" + UUID.randomUUID(), MIME, bytes);
        var completion = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
        try {
            var saved =
                    tx.execute(
                            status -> {
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCompletion(int value) {
                                                completion.set(value);
                                            }
                                        });
                                return archives.insert(
                                        tenant, actor, hash, name, key, bytes.length);
                            });
            if (saved == null) throw new IllegalStateException("原文件归档未返回结果");
            if (!key.equals(saved.key())) storage.discard(key);
            return saved;
        } catch (RuntimeException e) {
            if (completion.get() == TransactionSynchronization.STATUS_ROLLED_BACK)
                storage.discard(key);
            throw e;
        }
    }

    private Item item(
            TransportFeeWorkbookReader.Row r,
            LogisticsCostDetailView c,
            String state,
            String message) {
        return new Item(
                r.line(),
                r.trackingNo(),
                r.amount(),
                r.remark(),
                c == null ? null : c.id(),
                c == null ? null : c.documentNo(),
                c == null ? null : c.revision(),
                state,
                message);
    }

    private static Item state(Item i, String state, String message) {
        return new Item(
                i.line(),
                i.trackingNo(),
                i.amount(),
                i.remark(),
                i.costId(),
                i.documentNo(),
                i.revision(),
                state,
                message);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException bad(String text) {
        return new BusinessException(ErrorCode.BAD_REQUEST, text, List.of());
    }
}
