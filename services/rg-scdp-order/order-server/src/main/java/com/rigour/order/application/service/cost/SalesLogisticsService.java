package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.SalesLogisticsModels.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.order.application.service.sales.OrderSalesOrderService;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class SalesLogisticsService {
    private final OrderSalesOrderService orders;
    private final OrderLogisticsLedgerService ledgers;
    private final OrderLogisticsCostStore costs;
    private final TransportRecordService transports;
    private final PlatformTransportStore platform;
    private final OrderLedgerSourceStore sources;
    private ShipmentTransportStore shipments;

    @org.springframework.beans.factory.annotation.Autowired
    void shipmentStore(ShipmentTransportStore value) {
        this.shipments = value;
    }

    public SalesLogisticsService(
            OrderSalesOrderService orders,
            OrderLogisticsLedgerService ledgers,
            OrderLogisticsCostStore costs,
            TransportRecordService transports,
            PlatformTransportStore platform,
            OrderLedgerSourceStore sources) {
        this.orders = orders;
        this.ledgers = ledgers;
        this.costs = costs;
        this.transports = transports;
        this.platform = platform;
        this.sources = sources;
    }

    private String tenant() {
        return AuthorizationContext.requireCurrent().tenantId().toString();
    }

    public Context context(Long id) {
        AuthorizationContext.requirePermission("order:logistics-cost:read");
        var order = orders.salesOrderForLogistics(id);
        var source = source(order.orderNo(), "order:logistics-cost:read", true);
        var rows = costs.linkedSales(tenant(), order.orderNo(), "order:logistics-cost:read");
        var platforms =
                platform.byCosts(tenant(), rows.stream().map(LogisticsCostDetailView::id).toList());
        var shipping =
                shipments == null
                        ? Map.<String, List<ShipmentTransportModels.Source>>of()
                        : shipments.byCosts(
                                tenant(), rows.stream().map(LogisticsCostDetailView::id).toList());
        BigDecimal allocated = BigDecimal.ZERO, confirmed = BigDecimal.ZERO;
        boolean unknown = false;
        for (var row : rows) {
            if ("VOID".equals(row.statusCode())) continue;
            BigDecimal amount =
                    TransportAllocationPlan.savedShares(row, platforms.get(row.id()))
                            .get(order.orderNo());
            if (amount == null) unknown = true;
            else {
                allocated = allocated.add(amount);
                if ("CONFIRMED".equals(row.statusCode())) confirmed = confirmed.add(amount);
            }
        }
        return new Context(
                source,
                rows.stream()
                        .map(
                                r ->
                                        TransportAllocationPlan.detail(
                                                r,
                                                platforms.get(r.id()),
                                                shipping.getOrDefault(r.id(), List.of())))
                        .toList(),
                unknown ? null : allocated,
                confirmed,
                valid(order));
    }

    public Map<String, Integer> counts(List<String> orderNos) {
        AuthorizationContext.requirePermission("order:logistics-cost:read");
        if (orderNos == null
                || orderNos.size() > 200
                || orderNos.stream().anyMatch(n -> n == null || n.length() > 100))
            throw bad("单据数量或编号无效");
        var visible = sources.visibleSalesNos(tenant(), orderNos, "order:logistics-cost:read");
        return sources.logisticsUsage(tenant(), visible, "order:logistics-cost:read");
    }

    public void validateManual(Long id, Draft draft) {
        var order = orders.salesOrderForLogistics(id);
        if (!valid(order)) throw bad("当前销售订单状态不可关联物流");
        if (!"DELIVERY".equals(draft.transportType())
                || !List.of(order.orderNo()).equals(draft.orderCodes()))
            throw bad("销售侧新增必须关联当前销售单，运输类型为销售订单配送");
        if ("HLL".equals(draft.carrierCode())) throw bad("货拉拉请选择已完成的同步运输记录");
        source(order.orderNo(), "order:logistics-cost:write", false);
    }

    @Transactional
    public Detail link(Long orderId, Link command) {
        AuthorizationContext.requirePermission("order:logistics-cost:write");
        var order = orders.salesOrderForLogistics(orderId);
        if (!valid(order)) throw bad("当前销售订单状态不可关联物流");
        source(order.orderNo(), "order:logistics-cost:write", false);
        if (command == null || command.transportId() == null || command.revision() == null)
            throw bad("请选择运输记录");
        platform.lockTenant(tenant());
        var old = transports.detail(command.transportId());
        var row = old.record();
        var codes =
                new ArrayList<String>(
                        old.platform() != null && old.platform().draft() != null
                                ? old.platform().draft().orderCodes()
                                : row.data().ledger() != null
                                        ? row.data().ledger().sources().stream()
                                                .map(LogisticsCostLedger.Source::orderCode)
                                                .filter(Objects::nonNull)
                                                .toList()
                                        : row.sources().stream()
                                                .map(LogisticsCostSourceView::documentNo)
                                                .toList());
        // 已有关系重放不更改版本、归属或已确认成本。
        if (codes.contains(order.orderNo())) return old;
        if (!"DRAFT".equals(row.statusCode()) || row.revision() != command.revision())
            throw bad("记录已变化或不是待确认，请刷新；已确认运输须先撤销");
        String type = row.data().typeCode();
        if (type != null && !type.isBlank() && !"DELIVERY".equals(type))
            throw bad("该运输已有非销售用途，请核对，不能更改为销售配送");
        if (old.platform() == null) throw bad("当前仅货拉拉支持选择已有运输；其他物流请手工录入");
        var p = old.platform().source();
        var carrier = p.latestOrder() == null ? p.order() : p.latestOrder();
        if (!"API".equals(p.sourceChannel()) || !"已完成".equals(carrier.status()))
            throw bad("请选择已完成的货拉拉同步运输记录");
        codes.add(order.orderNo());
        var prior = old.platform().draft();
        return transports.saveWithPendingEvidence(
                row.id(),
                new Draft(
                        row.revision(),
                        "DELIVERY",
                        java.time.LocalDate.parse(carrier.useTime().substring(0, 10)),
                        "HLL",
                        carrier.raw().get("运单号"),
                        carrier.orderNo(),
                        prior == null ? null : prior.warehouseCode(),
                        prior == null ? null : prior.senderName(),
                        prior == null ? null : prior.senderContact(),
                        prior == null ? null : prior.senderPhone(),
                        prior == null ? null : prior.senderAddress(),
                        prior == null ? null : prior.cityCode(),
                        carrier.facts() == null ? null : carrier.facts().billAmount(),
                        prior == null ? null : prior.ownerEmployeeCode(),
                        prior == null ? null : prior.remark(),
                        command.reason() == null || command.reason().isBlank()
                                ? "销售订单选择物流"
                                : command.reason(),
                        prior == null ? null : prior.zeroReason(),
                        codes,
                        row.data().requestId(),
                        row.data().paymentStatusCode(),
                        List.of()));
    }

    private LogisticsCostSourceView source(String no, String action, boolean historical) {
        return ledgers.salesSources(List.of(no), action, historical).stream()
                .filter(r -> no.equals(r.documentNo()))
                .findFirst()
                .orElseThrow(() -> bad("销售订单不存在、状态无效或无权访问"));
    }

    private static boolean valid(SalesOrderDetailView row) {
        return row != null
                && row.orderStatusCode() != null
                && !Set.of("DRAFT", "CANCELLED").contains(row.orderStatusCode());
    }

    public static BigDecimal allocated(LogisticsCostDetailView row, String no) {
        if (row.data().amount() == null || row.data().ledger() == null) return null;
        return row.data().ledger().sources().stream()
                .filter(s -> no.equals(s.orderCode()))
                .flatMap(s -> s.allocations().stream())
                .map(LogisticsCostLedger.Allocation::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
