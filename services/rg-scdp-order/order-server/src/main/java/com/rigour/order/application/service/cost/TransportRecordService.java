package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsCostLedger.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.math.MoneyAllocation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 产品主列表复用原成本事实，自动来源保持只读；人工金额在服务端精确分摊。 */
@Service
public class TransportRecordService {
    private final OrderLogisticsCostService costs;
    private final OrderLogisticsLedgerService ledgers;
    private final OrderLogisticsReferences refs;
    private final PlatformTransportStore platform;
    private final CostAttachmentStorage attachments;

    public TransportRecordService(
            OrderLogisticsCostService costs,
            OrderLogisticsLedgerService ledgers,
            OrderLogisticsReferences refs,
            PlatformTransportStore platform,
            CostAttachmentStorage attachments) {
        this.costs = costs;
        this.ledgers = ledgers;
        this.refs = refs;
        this.platform = platform;
        this.attachments = attachments;
    }

    private String tenant() {
        var c = AuthorizationContext.requireCurrent();
        if (c.tenantId() == null) throw new AuthorizationDeniedException("tenant");
        return c.tenantId().toString();
    }

    public Page page(LogisticsCostQuery q, boolean export) {
        var p = export ? costs.export("logistics", q) : costs.entries("logistics", q);
        return new Page(
                p,
                platform.byCosts(
                        tenant(), p.items().stream().map(LogisticsCostDetailView::id).toList()));
    }

    public List<Detail> selected(List<String> ids) {
        var rows = costs.exportSelected(ids);
        var sources = platform.byCosts(tenant(), ids);
        return rows.stream().map(r -> new Detail(r, sources.get(r.id()))).toList();
    }

    public Detail detail(String id) {
        var row = costs.entry("logistics", id);
        return new Detail(row, platform.detail(tenant(), id));
    }

    public String voucher(String id, String voucherId) {
        var d = detail(id);
        if (d.platform() == null) throw bad("平台凭证不存在");
        var source = d.platform().source();
        var order = source.latestOrder() == null ? source.order() : source.latestOrder();
        if (order.facts() == null || order.facts().fees() == null) throw bad("平台凭证尚未取得");
        var vouchers = new ArrayList<com.rigour.order.api.v1.model.LogisticsImportModels.Voucher>();
        for (var fee : order.facts().fees())
            if (fee.vouchers() != null) vouchers.addAll(fee.vouchers());
        if (order.facts().receipts() != null) vouchers.addAll(order.facts().receipts());
        if (order.facts().billVouchers() != null) vouchers.addAll(order.facts().billVouchers());
        for (var v : vouchers)
            if (voucherId.equals(v.id())
                    && "READY".equals(v.state())
                    && v.fileKey() != null
                    && v.fileKey().startsWith(tenant() + "/integration-huolala-vouchers/")
                    && !v.fileKey().contains("..")) return attachments.url(v.fileKey());
        throw bad("凭证正在获取，请稍后重试");
    }

    @Transactional
    public Detail save(String id, Draft d) {
        AuthorizationContext.requirePermission("order:logistics-cost:write");
        validate(d);
        var old = id == null ? null : detail(id);
        if (old != null && !"DRAFT".equals(old.record().statusCode())) throw bad("只有待确认记录可编辑，请先撤销");
        if (old != null && !Objects.equals(old.record().revision(), d.revision()))
            throw bad("记录已变化，请刷新");
        if (old == null)
            try {
                UUID.fromString(d.requestId());
            } catch (Exception e) {
                throw bad("保存标识无效，请重新打开新增页面");
            }
        if (old != null
                && old.platform() == null
                && old.record().data().ledger() != null
                && (old.record().data().ledger().feeItems().size() != 1
                        || old.record().data().ledger().feeItems().stream()
                                .anyMatch(
                                        f ->
                                                !"COMPANY".equals(f.bearerCode())
                                                        || !"EQUAL"
                                                                .equals(f.allocationMethodCode()))))
            throw bad("此记录有多项费用或特殊分摊，请使用费用明细编辑，避免覆盖原分摊");
        var sources = documents(d, "order:logistics-cost:write");
        if (old != null && old.platform() != null) {
            var source = old.platform().source();
            var order = source.latestOrder() == null ? source.order() : source.latestOrder();
            var f = order.facts();
            if (!"HLL".equals(d.carrierCode())
                    || !Objects.equals(d.platformOrderNo(), order.orderNo())
                    || !Objects.equals(
                            blankToNull(d.trackingNo()),
                            blankToNull(order.raw() == null ? null : order.raw().get("运单号")))
                    || !Objects.equals(d.date().toString(), order.useTime().substring(0, 10))
                    || f != null && !same(d.amount(), f.billAmount()))
                throw bad("平台物流公司、订单号、日期和金额只读");
            // 未关联也能维护负责人、备注，暂不要求抄录平台未提供的地址。
            platform.saveDraft(
                    tenant(),
                    id,
                    d,
                    sources,
                    AuthorizationContext.requireCurrent().principalId().toString());
            return detail(id);
        }
        if (sources.isEmpty()) throw bad("请选择关联业务订单");
        var saved =
                costs.save(
                        "logistics",
                        id,
                        command(
                                old == null ? null : old.record(),
                                d,
                                sources,
                                "order:logistics-cost:write"));
        return detail(saved.id());
    }

    @Transactional
    public Detail confirm(String id, LogisticsCostActionCommand action) {
        AuthorizationContext.requirePermission("order:logistics-cost:confirm");
        var d = detail(id);
        if (d.record().revision() != action.revision()) throw bad("记录已变化，请刷新");
        if (d.platform() != null) {
            var draft = d.platform().draft();
            if (draft == null) throw bad("请先关联业务订单");
            var source = d.platform().source();
            var order = source.latestOrder() == null ? source.order() : source.latestOrder();
            BigDecimal amount = order.facts() == null ? null : order.facts().billAmount();
            if (amount == null) throw bad("平台账单金额尚未取得，请同步后确认");
            var from =
                    order.facts().stops() == null || order.facts().stops().isEmpty()
                            ? null
                            : order.facts().stops().getFirst();
            var latest =
                    new Draft(
                            d.record().revision(),
                            draft.transportType(),
                            draft.date(),
                            "HLL",
                            order.raw() == null ? null : order.raw().get("运单号"),
                            order.orderNo(),
                            draft.warehouseCode(),
                            first(draft.senderName(), from == null ? null : from.name()),
                            first(draft.senderContact(), from == null ? null : from.contactName()),
                            first(draft.senderPhone(), from == null ? null : from.contactPhone()),
                            first(draft.senderAddress(), from == null ? null : from.address()),
                            draft.cityCode(),
                            amount,
                            draft.ownerEmployeeCode(),
                            draft.remark(),
                            action.reason(),
                            draft.zeroReason(),
                            draft.orderCodes(),
                            draft.requestId());
            var existing = d.record().data().ledger();
            boolean custom =
                    existing != null
                            && (existing.feeItems().size() != 1
                                    || existing.feeItems().stream()
                                            .anyMatch(
                                                    f ->
                                                            !"COMPANY".equals(f.bearerCode())
                                                                    || !"EQUAL"
                                                                            .equals(
                                                                                    f
                                                                                            .allocationMethodCode())));
            LogisticsCostCommand checked;
            if (custom) {
                var codes =
                        existing.sources().stream()
                                .map(Source::orderCode)
                                .filter(Objects::nonNull)
                                .collect(java.util.stream.Collectors.toSet());
                if (!codes.equals(new HashSet<>(draft.orderCodes()))
                        || !Objects.equals(existing.transportPurposeCode(), draft.transportType()))
                    throw bad("订单关联已变化，请重新维护原费用分摊，不能自动替换自定义分摊");
                var c = d.record().data();
                checked =
                        new LogisticsCostCommand(
                                c.requestId(),
                                d.record().revision(),
                                c.title(),
                                c.typeCode(),
                                c.cityCode(),
                                c.cityName(),
                                c.departmentId(),
                                draft.ownerEmployeeCode(),
                                c.costDate(),
                                c.amount(),
                                c.paymentStatusCode(),
                                c.logistics(),
                                draft.remark(),
                                action.reason(),
                                existing);
            } else
                checked =
                        command(
                                d.record(),
                                latest,
                                documents(latest, "order:logistics-cost:confirm"),
                                "order:logistics-cost:confirm");
            var saved = costs.preparePlatformConfirmation(id, checked);
            action = new LogisticsCostActionCommand(saved.revision(), action.reason());
        }
        costs.action("logistics", id, action, true);
        return detail(id);
    }

    private List<LogisticsCostSourceView> documents(Draft d, String action) {
        var types =
                Map.of(
                        "DELIVERY",
                        "SALES_ORDER",
                        "TRANSFER",
                        "TRANSFER_ORDER",
                        "OTHER",
                        "OTHER_OUT");
        if (d.orderCodes() == null
                || d.orderCodes().size() > 50
                || new HashSet<>(d.orderCodes()).size() != d.orderCodes().size())
            throw bad("关联订单最多50张且不可重复");
        if (d.orderCodes().isEmpty()) return List.of();
        if (!types.containsKey(Objects.toString(d.transportType(), ""))) throw bad("请先选择运输类型");
        var result = new ArrayList<LogisticsCostSourceView>();
        for (String code : d.orderCodes()) {
            if (code == null || code.length() > 100) throw bad("业务单号无效");
            var q =
                    new LogisticsLedgerSourceQuery(
                            types.get(d.transportType()),
                            code,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            0,
                            true,
                            false);
            result.add(
                    ledgers.sources(q, action).stream()
                            .filter(r -> code.equals(r.documentNo()))
                            .findFirst()
                            .orElseThrow(() -> bad("业务单据不存在或无权限：" + code)));
        }
        return result.stream()
                .sorted(Comparator.comparing(LogisticsCostSourceView::documentNo))
                .toList();
    }

    private LogisticsCostCommand command(
            LogisticsCostDetailView old,
            Draft d,
            List<LogisticsCostSourceView> rows,
            String action) {
        if (rows.isEmpty()) throw bad("请先关联业务订单");
        String city = first(d.cityCode(), rows.getFirst().cityCode());
        if (city == null) throw bad("请维护业务单据的归属城市后再确认");
        String cityName = refs.city(city, action);
        Party sender;
        if (d.warehouseCode() != null && !d.warehouseCode().isBlank()) {
            var w =
                    ledgers
                            .parties("WAREHOUSE", d.warehouseCode(), null, null, null, 0, action)
                            .stream()
                            .filter(p -> d.warehouseCode().equals(p.code()))
                            .findFirst()
                            .orElseThrow(() -> bad("发货仓库不存在或无权限"));
            sender =
                    new Party(
                            "FROM",
                            "WAREHOUSE",
                            w.code(),
                            null,
                            w.name(),
                            first(d.senderContact(), w.contactName()),
                            first(d.senderPhone(), w.phone()),
                            first(d.senderAddress(), w.address()),
                            w.cityCode());
        } else
            sender =
                    new Party(
                            "FROM",
                            "OTHER",
                            null,
                            d.senderName(),
                            d.senderName(),
                            d.senderContact(),
                            d.senderPhone(),
                            d.senderAddress(),
                            city);
        var points = new ArrayList<Party>();
        var sources = new ArrayList<Source>();
        var allocations =
                d.amount() == null
                        ? Collections.nCopies(rows.size(), BigDecimal.ZERO)
                        : MoneyAllocation.split(
                                d.amount(), Collections.nCopies(rows.size(), BigDecimal.ONE));
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String point = "TO" + i;
            points.add(
                    new Party(
                            point,
                            "CUSTOMER",
                            row.receiverCode(),
                            null,
                            row.receiverName(),
                            row.contactName(),
                            row.contactPhone(),
                            row.destinationAddress(),
                            row.cityCode()));
            if (!"SALES_ORDER".equals(row.sourceType())) {
                String kind = "TRANSFER_ORDER".equals(row.sourceType()) ? "WAREHOUSE" : "OTHER";
                points.set(
                        i,
                        new Party(
                                point,
                                kind,
                                "WAREHOUSE".equals(kind) ? row.receiverCode() : null,
                                row.receiverName(),
                                row.receiverName(),
                                row.contactName(),
                                row.contactPhone(),
                                row.destinationAddress(),
                                row.cityCode()));
            }
            sources.add(
                    new Source(
                                    row.sourceType(),
                                    row.documentNo(),
                                    "COST",
                                    List.of(
                                            new Allocation(
                                                    "freight",
                                                    point,
                                                    row.cityCode(),
                                                    BigDecimal.ONE,
                                                    allocations.get(i))),
                                    null)
                            .attributed(row));
        }
        Long department = null;
        if (d.ownerEmployeeCode() != null && !d.ownerEmployeeCode().isBlank())
            department =
                    refs.employees(action).stream()
                            .filter(e -> d.ownerEmployeeCode().equals(e.employeeCode()))
                            .findFirst()
                            .orElseThrow(() -> bad("费用负责人不存在"))
                            .departmentId();
        var ledger =
                new LogisticsCostLedger(
                                2,
                                d.transportType(),
                                "SELF".equals(d.carrierCode()) ? "SELF" : "THIRD_PARTY",
                                d.carrierCode(),
                                d.date().toString().substring(0, 7),
                                d.trackingNo(),
                                null,
                                sender,
                                points,
                                List.of(
                                        new Fee(
                                                "freight",
                                                "FREIGHT",
                                                d.amount(),
                                                "COMPANY",
                                                d.amount(),
                                                "EQUAL",
                                                d.zeroReason(),
                                                null)),
                                sources,
                                "OTHER".equals(d.transportType())
                                        ? first(d.remark(), "其他订单运输")
                                        : null,
                                "实际无运单号",
                                null,
                                List.of(),
                                List.of())
                        .withPlatformOrderNo(
                                "HLL".equals(d.carrierCode()) ? d.platformOrderNo() : null);
        return new LogisticsCostCommand(
                old == null ? d.requestId() : old.data().requestId(),
                old == null ? null : old.revision(),
                "物流运输",
                d.transportType(),
                city,
                cityName,
                department,
                d.ownerEmployeeCode(),
                d.date(),
                d.amount(),
                old == null ? "UNKNOWN" : old.data().paymentStatusCode(),
                null,
                d.remark(),
                d.reason(),
                ledger);
    }

    private void validate(Draft d) {
        if (d == null
                || d.date() == null
                || d.remark() != null && d.remark().length() > 1000
                || d.reason() != null && d.reason().length() > 500) throw bad("运输日期、备注或原因无效");
        for (String value :
                Arrays.asList(
                        d.carrierCode(), d.warehouseCode(), d.cityCode(), d.ownerEmployeeCode()))
            if (value != null && value.length() > 64) throw bad("档案编码过长");
        for (String value :
                Arrays.asList(
                        d.trackingNo(), d.platformOrderNo(), d.senderName(), d.senderContact()))
            if (value != null && value.length() > 100) throw bad("单号或名称过长");
        if (d.senderPhone() != null && d.senderPhone().length() > 50
                || d.senderAddress() != null && d.senderAddress().length() > 500)
            throw bad("发货联系方式或地址过长");
        try {
            com.rigour.order.domain.cost.LogisticsCostRules.money(d.amount(), false);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    private static String first(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
