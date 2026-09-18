package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.SalesOrderProductRepair.*;
import com.rigour.order.application.port.out.ErpOrderRepairCatalog;
import com.rigour.order.application.port.out.ErpOrderRepairCatalog.Query;
import com.rigour.order.application.port.out.OrderProductRepairStore;
import com.rigour.order.application.port.out.OrderRepairUnitDictionary;
import com.rigour.order.application.port.out.OrderProductRepairStore.*;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 人工复核历史商品引用；ERP 实体存在和唯一不代表历史换算成立，换算必须另有证据。 */
@Service
public final class OrderProductRepairService {
    private final OrderProductRepairStore store;
    private final ErpOrderRepairCatalog catalog;
    private final Clock clock;
    private final OrderRepairUnitDictionary units;

    public OrderProductRepairService(OrderProductRepairStore store, ErpOrderRepairCatalog catalog, Clock clock,
                                     OrderRepairUnitDictionary units) {
        this.store = store;
        this.catalog = catalog;
        this.clock = clock;
        this.units = units;
    }

    public Context context(Long id) {
        var actor = actor();
        String tenant = actor.tenantId().toString();
        var order = accessible(actor, id);
        return new Context(id, order.orderNo(), order.sourceOrderNo(), order.revision(), order.lines().stream()
                .map(line -> new CurrentLine(line.value(), line.revision(),
                        store.currentRepair(tenant, id, line.value().id(), line.revision()).orElse(null))).toList(),
                store.recentRepairs(tenant, id));
    }

    public EvidencePage evidence(long afterLineId, int limit) {
        var actor = readActor();
        if (afterLineId < 0 || limit < 1 || limit > 20000) throw bad("证据分页范围无效，limit必须在1到20000之间");
        var rows = store.evidence(actor.tenantId().toString(), (active() || actor.roles().contains("TENANT_SUPER_ADMIN"))
                ? null : actor.userId().toString(), afterLineId, limit + 1);
        boolean more = rows.size() > limit;
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new EvidencePage(page, page.isEmpty() ? afterLineId : page.getLast().lineId(), more, clock.instant());
    }

    public Preview preview(Long id, PreviewCommand command) {
        var actor = actor();
        var order = accessible(actor, id);
        eligible(order);
        if (command == null || command.revision() == null
                || !command.revision().equals(order.revision())) throw conflict("订单已变化，请重新载入");
        String reason = required(command.reason(), 1000, "修复原因");
        if (command.lines() == null || command.lines().isEmpty() || command.lines().size() > 100) {
            throw bad("每次修复必须选择1到100条订单明细");
        }
        var ids = new HashSet<Long>();
        var validUnits = unitCodes(actor.tenantId().toString(), command.lines());
        List<LinePreview> lines = new ArrayList<>();
        for (var raw : command.lines()) {
            if (raw == null || raw.lineId() == null || !ids.add(raw.lineId())) throw bad("明细ID为空或重复");
            var line = order.lines().stream().filter(l -> raw.lineId().equals(l.value().id())).findFirst()
                    .orElseThrow(() -> bad("明细不属于当前订单或已删除"));
            lines.add(evaluate(actor.tenantId().toString(), line, normalize(raw), validUnits));
        }
        var now = clock.instant();
        var preview = new Preview(UUID.randomUUID().toString(), id, order.orderNo(), order.revision(),
                lines.stream().allMatch(l -> l.blockers().isEmpty()) ? "READY" : "BLOCKED",
                now.plus(Duration.ofMinutes(15)), reason, actor.userId().toString(), now, List.copyOf(lines));
        store.save(actor.tenantId().toString(), new Saved(preview, order.fingerprint(), null));
        return preview;
    }

    public Applied apply(Long id, String previewId, ApplyCommand command) {
        var actor = actor();
        var order = accessible(actor, id);
        if (command == null || !Boolean.TRUE.equals(command.confirm())) throw bad("必须显式确认预览");
        String tenant = actor.tenantId().toString();
        var saved = store.preview(tenant, id, required(previewId, 36, "预览ID"))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预览不存在", List.of()));
        var preview = saved.preview();
        if (!preview.createdBy().equals(actor.userId().toString())) {
            throw new AuthorizationDeniedException("order-product-repair-preview-owner");
        }
        if (saved.applied() != null) return saved.applied();
        eligible(order);
        if (!"READY".equals(preview.status()) || !clock.instant().isBefore(preview.expiresAt())
                || !order.fingerprint().equals(saved.fingerprint())) throw conflict("预览已失效或仍有阻断项，请重新预览");
        var validUnits = unitCodes(tenant, preview.lines().stream().map(LinePreview::requested).toList());
        for (var line : preview.lines()) {
            var verified = evaluate(tenant, new Line(line.original(), line.lineRevision()), line.requested(), validUnits);
            if (!verified.blockers().isEmpty() || !Objects.equals(line.proposed(), verified.proposed())) {
                throw conflict("ERP商品或规格已变化，请重新预览");
            }
        }
        return store.apply(tenant, id, previewId, actor.userId().toString(), clock.instant());
    }

    private Set<String> unitCodes(String tenant, List<LineCommand> commands) {
        return commands.stream().filter(Objects::nonNull).anyMatch(c -> c.historicalTransactionUnitCode() != null
                || c.standardUnitCode() != null) ? units.validUnits(tenant) : Set.of();
    }

    private LinePreview evaluate(String tenant, Line line, LineCommand command, Set<String> validUnits) {
        var original = line.value();
        List<String> blockers = new ArrayList<>();
        boolean excluded = original.quantity() == null || original.quantity().signum() <= 0;
        if (excluded) {
            blockers.add("零数量、负数量和退款排除行不可修复");
        }
        if (command.bindingEvidence() == null) blockers.add("必须填写原始商品与ERP商品对应的复核依据");
        List<Candidate> candidates = List.of();
        if (!excluded) {
            candidates = catalog.candidates(tenant, new Query(command.productCode(),
                    command.productCode() == null ? original.productNameSnapshot() : null,
                    command.skuCode(), command.skuCode() == null ? original.specificationSnapshot() : null));
        }
        Candidate proposed = candidates.size() == 1 ? candidates.getFirst() : null;
        if (proposed == null) blockers.add(candidates.isEmpty() ? "未找到精确匹配的有效ERP商品/SKU" : "匹配存在歧义，必须指定唯一商品编码与SKU编码");
        else if (proposed.productId() == null || proposed.productVariantId() == null
                || proposed.productId() <= 0 || proposed.productVariantId() <= 0
                || proposed.productRevision() == null || proposed.variantRevision() == null
                || proposed.productCode() == null || proposed.skuCode() == null || proposed.unitCode() == null) {
            blockers.add("ERP商品/SKU信息无效");
        }
        boolean unitConfirmed = command.historicalTransactionUnitCode() != null && command.transactionUnitEvidence() != null
                && Boolean.TRUE.equals(command.confirmHistoricalTransactionUnit());
        boolean unitRequested = command.historicalTransactionUnitCode() != null || command.transactionUnitEvidence() != null
                || Boolean.TRUE.equals(command.confirmHistoricalTransactionUnit());
        if (unitRequested && !unitConfirmed) blockers.add("历史交易单位必须同时提供单位、凭证依据和显式确认");
        if (command.historicalTransactionUnitCode() != null && !validUnits.contains(command.historicalTransactionUnitCode())) {
            blockers.add("历史交易单位不是当前有效商品单位字典项");
        }
        if (command.standardUnitCode() != null && !validUnits.contains(command.standardUnitCode())) {
            blockers.add("标准单位不是当前有效商品单位字典项");
        }
        boolean sourceConfirmed = command.sourceNamespace() != null && command.sourceCaptureRef() != null
                && (command.sourceProductRecordId() != null || command.sourceProductCode() != null)
                && command.sourceEvidence() != null && Boolean.TRUE.equals(command.confirmSourceIdentity());
        boolean sourceRequested = command.sourceNamespace() != null || command.sourceCaptureRef() != null
                || command.sourceProductRecordId() != null || command.sourceProductCode() != null
                || command.sourceEvidence() != null || Boolean.TRUE.equals(command.confirmSourceIdentity());
        if (sourceRequested && !sourceConfirmed) blockers.add("来源对应必须提供来源命名空间、采集引用、来源商品记录ID或编码、证据及人工确认");
        boolean standard = command.standardQuantity() != null || command.standardUnitCode() != null
                || command.conversionFactor() != null || command.conversionEvidence() != null
                || Boolean.TRUE.equals(command.confirmHistoricalConversion());
        if (standard) {
            if (!unitConfirmed) blockers.add("数据库原单位可能来自ERP默认值；标准计量必须先确认有证据的历史交易单位");
            if (!positiveDecimal(command.standardQuantity()) || !positiveDecimal(command.conversionFactor())
                    || command.standardUnitCode() == null || command.conversionEvidence() == null
                    || !Boolean.TRUE.equals(command.confirmHistoricalConversion())) {
                blockers.add("标准计量必须同时提供有效数量、单位、历史换算系数、证据和显式确认");
            } else {
                if (proposed != null && !command.standardUnitCode().equals(proposed.unitCode())) blockers.add("标准单位必须等于所选ERP SKU单位");
                if (original.quantity() == null || original.quantity().multiply(command.conversionFactor())
                        .compareTo(command.standardQuantity()) != 0) blockers.add("标准数量必须精确等于原交易数量乘历史换算系数");
                if (command.standardUnitCode().equals(command.historicalTransactionUnitCode())
                        && command.conversionFactor().compareTo(BigDecimal.ONE) != 0) blockers.add("同单位换算系数必须为1");
            }
        }
        return new LinePreview(original, line.revision(), command, List.copyOf(candidates), proposed,
                sourceConfirmed ? "OPERATOR_CONFIRMED" : "UNVERIFIED",
                unitConfirmed ? "OPERATOR_CONFIRMED" : "UNVERIFIED", List.copyOf(blockers));
    }

    private Snapshot accessible(CallerIdentity actor, Long id) {
        if (id == null || id <= 0) throw bad("订单ID无效");
        var order = store.snapshot(actor.tenantId().toString(), id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "销售订单不存在", List.of()));
        if (!active() && !actor.roles().contains("TENANT_SUPER_ADMIN")
                && !actor.userId().toString().equals(order.ownerSalesUserId())) {
            throw new AuthorizationDeniedException("order-product-repair-data-scope");
        }
        if (!"FEISHU".equals(order.sourceSystemCode())) throw bad("只允许修复FEISHU历史订单");
        return order;
    }

    private static boolean active(){return com.rigour.tenant.iam.client.SupplyAuthorizationContext.current().map(com.rigour.tenant.iam.client.SupplyAuthorizationContext::active).orElse(false);}

    private static CallerIdentity actor() {
        var actor = readActor();
        AuthorizationContext.requirePermission(active()?"order:history:repair":"order:write");
        return actor;
    }

    private static CallerIdentity readActor() {
        var actor = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission("order:read");
        if (!"TENANT".equals(actor.principalScope()) || actor.tenantId() == null || actor.userId() == null) {
            throw new AuthorizationDeniedException("tenant-operator-required");
        }
        return actor;
    }

    private static void eligible(Snapshot order) {
        if ("CANCELLED".equals(order.orderStatusCode()) || order.totalQuantity() == null
                || order.totalQuantity().signum() <= 0) throw conflict("已取消或零/负数量订单不可修复");
    }

    private static LineCommand normalize(LineCommand c) {
        return new LineCommand(c.lineId(), clean(c.productCode(), 128), clean(c.skuCode(), 128),
                clean(c.bindingEvidence(), 2000), reference(c.sourceNamespace(), 200), reference(c.sourceCaptureRef(), 500),
                clean(c.sourceProductRecordId(), 200), clean(c.sourceProductCode(), 200), clean(c.sourceEvidence(), 2000),
                c.confirmSourceIdentity(), clean(c.historicalTransactionUnitCode(), 64),
                clean(c.transactionUnitEvidence(), 2000), c.confirmHistoricalTransactionUnit(),
                c.standardQuantity(), clean(c.standardUnitCode(), 64),
                c.conversionFactor(), clean(c.conversionEvidence(), 2000), c.confirmHistoricalConversion());
    }

    private static boolean positiveDecimal(BigDecimal value) {
        return value != null && value.signum() > 0 && value.stripTrailingZeros().scale() <= 6
                && value.precision() - value.scale() <= 18;
    }
    private static String reference(String value, int max) {
        String ref = clean(value, max);
        if (ref != null && !ref.matches("[A-Za-z0-9][A-Za-z0-9:_.\\-/]{0," + (max - 1) + "}")) {
            throw bad("来源命名空间和采集引用必须使用来源ID、采集ID或批次ID，不能填写说明文案");
        }
        return ref;
    }
    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw bad("字段超出长度限制: " + max);
        if (value.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')) {
            throw bad("字段含无效控制字符");
        }
        return value.strip();
    }
    private static String required(String value, int max, String label) {
        String result = clean(value, max);
        if (result == null) throw bad(label + "不能为空");
        return result;
    }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of()); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message, List.of()); }
}
