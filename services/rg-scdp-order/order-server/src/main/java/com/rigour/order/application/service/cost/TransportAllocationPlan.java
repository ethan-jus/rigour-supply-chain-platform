package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.LogisticsCostDetailView;
import com.rigour.order.api.v1.model.LogisticsCostLedger;
import com.rigour.order.api.v1.model.LogisticsCostSourceView;
import com.rigour.order.api.v1.model.ShipmentTransportModels;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.math.MoneyAllocation;

import java.math.BigDecimal;
import java.util.*;

/** 同一运输只分配一次总额；保存、修改及来源刷新使用同一份金额计划。 */
public final class TransportAllocationPlan {
    private TransportAllocationPlan() {}

    public record Plan(
            String method, Map<String, BigDecimal> shares, Map<String, BigDecimal> bases) {}

    public static Detail detail(
            LogisticsCostDetailView row,
            PlatformRecord platform,
            List<ShipmentTransportModels.Source> shipments) {
        return new Detail(row, platform, shipments, savedShares(row, platform));
    }

    /** 已有台账始终读取保存的成本分摊；历史无台账的来源记录才从关联信息恢复。 */
    public static Map<String, BigDecimal> savedShares(
            LogisticsCostDetailView row, PlatformRecord platform) {
        var ledger = row.data().ledger();
        if (ledger == null && platform != null && platform.draft() != null) {
            var draft = platform.draft();
            var source =
                    platform.source().latestOrder() == null
                            ? platform.source().order()
                            : platform.source().latestOrder();
            var amount = row.data().amount();
            var snapshots =
                    row.sources() == null ? List.<LogisticsCostSourceView>of() : row.sources();
            if ((snapshots.isEmpty() || snapshots.stream().anyMatch(s -> s.documentNo() == null))
                    && ledger != null
                    && ledger.sources() != null) {
                snapshots =
                        ledger.sources().stream()
                                .map(
                                        s ->
                                                new LogisticsCostSourceView(
                                                                null,
                                                                s.orderCode(),
                                                                s.orderTypeCode(),
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                null)
                                                        .withBusinessSnapshot(
                                                                s.documentAmount(), s.linkedAt()))
                                .toList();
            }
            if (snapshots.size() != draft.orderCodes().size()
                    || !new HashSet<>(
                                    snapshots.stream()
                                            .map(LogisticsCostSourceView::documentNo)
                                            .toList())
                            .equals(new HashSet<>(draft.orderCodes()))) return Map.of();
            try {
                return calculate(amount, snapshots, draft.orderAllocations()).shares();
            } catch (BusinessException changedBill) {
                // 来源金额变化后旧分摊不再相等，保留草稿供业务重新分摊，不能以0替代。
                return Map.of();
            }
        }
        var shares = new LinkedHashMap<String, BigDecimal>();
        if (ledger != null && ledger.sources() != null)
            for (var link : ledger.sources()) {
                if (link.orderCode() == null || !"COST".equals(link.linkRoleCode())) continue;
                shares.put(
                        link.orderCode(),
                        row.data().amount() == null
                                        || link.allocations() == null
                                        || link.allocations().isEmpty()
                                ? null
                                : link.allocations().stream()
                                        .map(LogisticsCostLedger.Allocation::amount)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
        return Collections.unmodifiableMap(shares);
    }

    /** 平台自动金额变化仅适用于未人工干预的单项运费，按原业务金额基数重算并守恒。 */
    public static com.rigour.order.api.v1.model.LogisticsCostCommand reprice(
            com.rigour.order.api.v1.model.LogisticsCostCommand command, BigDecimal total) {
        if (Objects.equals(command.amount(), total)) return command;
        var ledger = command.ledger();
        if (ledger == null) return command.withLedger(null, total);
        if (complex(ledger)) throw bad("已有自定义费用分摊，来源同步不能重算成本");
        var links =
                ledger.sources().stream()
                        .sorted(Comparator.comparing(LogisticsCostLedger.Source::orderCode))
                        .toList();
        boolean weighted =
                links.size() > 1
                        && links.stream()
                                .allMatch(
                                        s ->
                                                s.documentAmount() != null
                                                        && s.documentAmount().signum() > 0);
        var weights =
                links.stream().map(s -> weighted ? s.documentAmount() : BigDecimal.ONE).toList();
        var values =
                links.isEmpty() || total == null
                        ? List.<BigDecimal>of()
                        : MoneyAllocation.split(total, weights);
        var result = new ArrayList<LogisticsCostLedger.Source>();
        for (int i = 0; i < links.size(); i++) {
            var s = links.get(i);
            var a = s.allocations().getFirst();
            result.add(
                    new LogisticsCostLedger.Source(
                            s.orderTypeCode(),
                            s.orderCode(),
                            s.linkRoleCode(),
                            List.of(
                                    new LogisticsCostLedger.Allocation(
                                            a.feeKey(),
                                            a.receivingPointCode(),
                                            a.cityCode(),
                                            weights.get(i),
                                            total == null ? BigDecimal.ZERO : values.get(i))),
                            s.noSourceReason(),
                            s.employeeCode(),
                            s.employeeName(),
                            s.departmentId(),
                            s.departmentName(),
                            s.sourceWarehouseCode(),
                            s.documentAmount(),
                            s.documentDate(),
                            s.linkedAt()));
        }
        var f = ledger.feeItems().getFirst();
        return command.withLedger(
                ledger.withSources(result)
                        .withFeeItems(
                                List.of(
                                        new LogisticsCostLedger.Fee(
                                                f.feeKey(),
                                                f.feeTypeCode(),
                                                total,
                                                f.bearerCode(),
                                                total,
                                                weighted ? "AMOUNT" : "EQUAL",
                                                f.zeroCostReason(),
                                                f.remark(),
                                                f.basisUnitCode()))),
                total);
    }

    public static Plan calculate(
            BigDecimal total,
            List<LogisticsCostSourceView> sources,
            Map<String, BigDecimal> manual) {
        if (sources == null
                || sources.stream()
                        .anyMatch(
                                s ->
                                        s == null
                                                || s.documentNo() == null
                                                || s.documentNo().isBlank()))
            throw bad("分摊业务单号无效或重复");
        var rows =
                sources.stream()
                        .sorted(Comparator.comparing(LogisticsCostSourceView::documentNo))
                        .toList();
        var shares = new LinkedHashMap<String, BigDecimal>();
        var bases = new LinkedHashMap<String, BigDecimal>();
        var codes = new HashSet<>(rows.stream().map(LogisticsCostSourceView::documentNo).toList());
        if (codes.size() != rows.size() || codes.contains(null)) throw bad("分摊业务单号无效或重复");
        if (total != null && (total.signum() < 0 || total.stripTrailingZeros().scale() > 2))
            throw bad("运输费用须为非负金额，最多两位小数");
        boolean custom = manual != null && !manual.isEmpty();
        if (custom) {
            if (total == null || !manual.keySet().equals(codes)) throw bad("请为全部关联订单填写分摊金额");
            BigDecimal sum = BigDecimal.ZERO;
            for (var row : rows) {
                BigDecimal value = manual.get(row.documentNo());
                if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 2)
                    throw bad("分摊金额须为非负金额，最多两位小数");
                shares.put(row.documentNo(), value.setScale(2));
                bases.put(row.documentNo(), BigDecimal.ONE);
                sum = sum.add(value);
            }
            if (sum.compareTo(total) != 0) throw bad("订单分摊合计必须等于运输费用");
            return new Plan("MANUAL", Collections.unmodifiableMap(shares), Map.copyOf(bases));
        }
        boolean weighted =
                rows.size() > 1
                        && rows.stream()
                                .allMatch(
                                        s ->
                                                "SALES_ORDER".equals(s.sourceType())
                                                        && s.documentAmount() != null
                                                        && s.documentAmount().signum() > 0
                                                        && s.documentAmount().scale() <= 6);
        rows.forEach(
                row ->
                        bases.put(
                                row.documentNo(),
                                weighted ? row.documentAmount() : BigDecimal.ONE));
        var values =
                total == null || rows.isEmpty()
                        ? List.<BigDecimal>of()
                        : MoneyAllocation.split(total, new ArrayList<>(bases.values()));
        for (int i = 0; i < rows.size(); i++)
            shares.put(rows.get(i).documentNo(), total == null ? null : values.get(i));
        return new Plan(
                weighted ? "AMOUNT" : "EQUAL",
                Collections.unmodifiableMap(shares),
                Map.copyOf(bases));
    }

    /** 一项我方运费、每单一个收货点可在统一表单调整；复杂承担／逐点分摊保留原明细编辑。 */
    public static boolean complex(LogisticsCostLedger ledger) {
        return ledger != null
                && (ledger.feeItems() == null
                        || ledger.feeItems().size() != 1
                        || ledger.feeItems().stream()
                                .anyMatch(
                                        f ->
                                                !"COMPANY".equals(f.bearerCode())
                                                        || !("EQUAL"
                                                                        .equals(
                                                                                f
                                                                                        .allocationMethodCode())
                                                                || "freight".equals(f.feeKey())
                                                                        && Set.of(
                                                                                        "AMOUNT",
                                                                                        "MANUAL")
                                                                                .contains(
                                                                                        f
                                                                                                .allocationMethodCode())))
                        || ledger.sources() != null
                                && ledger.sources().stream()
                                        .anyMatch(
                                                s ->
                                                        !"COST".equals(s.linkRoleCode())
                                                                || s.allocations() == null
                                                                || s.allocations().size() > 1));
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
