package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;
import com.rigour.order.api.v1.model.OrderPayrollSettlementView;
import com.rigour.order.application.port.out.OrderCostSourceStore;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;
import com.rigour.order.infrastructure.persistence.mapper.OrderCostSourceMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class MybatisPlusOrderCostSourceRepository implements OrderCostSourceStore {
    private final OrderCostSourceMapper mapper;
    private final LogisticsWarehouseDataScope scope;

    public MybatisPlusOrderCostSourceRepository(
            OrderCostSourceMapper mapper, LogisticsWarehouseDataScope scope) {
        this.mapper = mapper;
        this.scope = scope;
    }

    @Override
    public List<OrderCollectionSourceView> collections(
            String tenant, String employee, LocalDate from, LocalDate to) {
        return mapper.collections(
                tenant,
                employee,
                from.atStartOfDay().minusHours(8),
                to.plusDays(1).atStartOfDay().minusHours(8));
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.rigour.order.api.v1.model.OrderCityCollectionSourceView> cityCollections(
            String tenant, String city, LocalDate from, LocalDate to) {
        var rows =
                mapper.cityCollections(
                        tenant,
                        city,
                        from.atStartOfDay().minusHours(8),
                        to.plusDays(1).atStartOfDay().minusHours(8));
        if (rows.size() > 20000)
            throw new com.rigour.shared.core.exception.BusinessException(
                    com.rigour.shared.core.api.ErrorCode.BAD_REQUEST,
                    "城市到账来源超过上限，请分期核对",
                    List.of());
        return rows;
    }

    @Override
    public List<OrderDeliveryCostSourceView> deliveries(
            String tenant, String keyword, List<Long> ids) {
        return deliveries(tenant, keyword, ids, null, 0, null);
    }

    public List<OrderDeliveryCostSourceView> deliveries(
            String tenant, String keyword, List<Long> ids, String city, int begin, String action) {
        var q =
                new QueryWrapper<InternalSalesOrderEntity>()
                        .eq("o.tenant_id", tenant)
                        .eq("o.deleted", 0)
                        .notIn("o.order_status_code", "DRAFT", "CANCELLED", "DELETED");
        // 导入订单可能尚未关联仓库。仍执行原仓库范围：指定仓库角色不会匹配 NULL，
        // 全仓角色可选择后在费用中补录实际仓库，不回写销售订单。
        q.eq(city != null && !city.isBlank(), "o.region_code", city);
        if (action != null) {
            var predicate = scope.predicate(action, "o.selected_warehouse_id");
            MybatisPlusOrderLogisticsCostRepository.bind(q, predicate.sql(), predicate.args());
            scope.applyCity(q, action, "o.region_code");
        }
        if (ids.isEmpty())
            q.and(
                    w ->
                            w.like("o.order_no", keyword)
                                    .or()
                                    .like("o.customer_name_snapshot", keyword)
                                    .or()
                                    .like("o.source_order_no", keyword));
        else q.in("o.id", ids);
        return mapper.deliveries(
                q.orderByDesc("o.id")
                        .last(ids.isEmpty() ? "LIMIT 50 OFFSET " + begin : "LIMIT 100"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderCommissionSourceLineView> commissions(
            String tenant, String employee, String city, LocalDate through) {
        var rows =
                mapper.commissions(
                        tenant, employee, city, through.plusDays(1).atStartOfDay().minusHours(8));
        if (rows.size() > 20000) throw new IllegalStateException("提成来源超过上限，请分期核对");
        return rows;
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.rigour.order.api.v1.model.OrderPayrollLineView> payrollLines(
            String tenant, String employee, String city, LocalDate through) {
        var rows =
                mapper.payrollLines(
                        tenant, employee, city, through.plusDays(1).atStartOfDay().minusHours(8));
        if (rows.size() > 20000) throw new IllegalStateException("付清订单来源超过上限，请分期核对");
        return rows;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderPayrollSettlementView> payrollSettlements(
            String tenant, String employee, LocalDate from, LocalDate to) {
        var settlements =
                mapper.payrollSettlements(
                        tenant, employee, from.atStartOfDay().minusHours(8),
                        to.plusDays(1).atStartOfDay().minusHours(8));
        if (settlements.isEmpty()) return List.of();
        requireSettlementLimit(settlements.size());
        var products = mapper.payrollProductLines(
                tenant, settlements.stream().map(OrderCostSourceMapper.PayrollSettlement::orderId).toList());
        requireSettlementLimit(products.size());
        Map<Long, List<OrderPayrollSettlementView.ProductLine>> linesByOrder = new HashMap<>();
        for (var product : products) {
            linesByOrder.computeIfAbsent(product.orderId(), ignored -> new ArrayList<>())
                    .add(new OrderPayrollSettlementView.ProductLine(
                            product.lineId(), product.productId(), product.productVariantId(),
                            product.productName(), product.unit(), product.quantity(), product.lineAmount()));
        }
        return settlements.stream()
                .map(settlement -> new OrderPayrollSettlementView(
                        Long.toString(settlement.orderId()), settlement.orderNo(), settlement.originalEmployeeCode(),
                        settlement.paymentId(), settlement.paymentNo(), settlement.paymentOwnerCode(),
                        settlement.paymentOwnerName(), settlement.paymentOwnerEvidence(),
                        settlement.fullyPaidDate(), settlement.actualCashDate(), settlement.orderAmount(),
                        settlement.refundedAmount(), settlement.qualityStatus(),
                        List.copyOf(linesByOrder.getOrDefault(settlement.orderId(), List.of()))))
                .toList();
    }

    private static void requireSettlementLimit(int count) {
        if (count > 20000)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回款核算来源超过上限，请分期核对", List.of());
    }
}
