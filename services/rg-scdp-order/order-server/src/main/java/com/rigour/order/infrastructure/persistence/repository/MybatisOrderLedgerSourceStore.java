package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.OrderLedgerSourceStore;
import com.rigour.order.infrastructure.persistence.mapper.OrderCostSourceMapper;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisOrderLedgerSourceStore implements OrderLedgerSourceStore {
    private final OrderCostSourceMapper mapper;
    private final LogisticsWarehouseDataScope scope;
    private final MybatisPlusOrderLogisticsCostRepository costs;

    public MybatisOrderLedgerSourceStore(
            OrderCostSourceMapper mapper,
            LogisticsWarehouseDataScope scope,
            MybatisPlusOrderLogisticsCostRepository costs) {
        this.mapper = mapper;
        this.scope = scope;
        this.costs = costs;
    }

    public java.util.Map<String, Integer> logisticsUsage(
            String tenant, List<String> documentNos, String action) {
        return costs.sourceUsage(tenant, documentNos, action);
    }

    public List<String> visibleSalesNos(String tenant, List<String> documentNos, String action) {
        if (documentNos.isEmpty()) return List.of();
        var q =
                new QueryWrapper<Object>()
                        .eq("o.tenant_id", tenant)
                        .eq("o.deleted", 0)
                        .in("o.order_no", documentNos);
        var p = scope.predicate(action, "o.selected_warehouse_id");
        MybatisPlusOrderLogisticsCostRepository.bind(q, p.sql(), p.args());
        scope.applyCity(q, action, "o.region_code");
        return mapper.deliveries(q).stream().map(OrderDeliveryCostSourceView::documentNo).toList();
    }

    public List<OrderDeliveryCostSourceView> sales(
            String tenant, LogisticsLedgerSourceQuery f, String action, Long warehouseId) {
        return sales(tenant, f, action, warehouseId, java.util.Set.of());
    }

    @Override
    public List<OrderDeliveryCostSourceView> sales(
            String tenant,
            LogisticsLedgerSourceQuery f,
            String action,
            Long warehouseId,
            java.util.Set<String> departmentEmployeeCodes) {
        var q =
                new QueryWrapper<Object>()
                        .eq("o.tenant_id", tenant)
                        .eq("o.deleted", 0)
                        .notIn(!f.historical(), "o.order_status_code", "DRAFT", "CANCELLED");
        var p = scope.predicate(action, "o.selected_warehouse_id");
        MybatisPlusOrderLogisticsCostRepository.bind(q, p.sql(), p.args());
        scope.applyCity(q, action, "o.region_code");
        q.eq(text(f.cityCode()), "o.region_code", f.cityCode());
        q.eq(warehouseId != null, "o.selected_warehouse_id", warehouseId);
        if (text(f.documentNo()))
            q.and(
                    w -> {
                        if (f.exact())
                            w.eq("o.order_no", f.documentNo())
                                    .or()
                                    .eq("o.source_order_no", f.documentNo());
                        else
                            w.like("o.order_no", f.documentNo())
                                    .or()
                                    .like("o.source_order_no", f.documentNo());
                    });
        if (text(f.employeeName()))
            q.apply(
                    "LOCATE({0},COALESCE(snap.employee_name,o.owner_employee_name_snapshot,''))>0",
                    f.employeeName());
        if (text(f.departmentName()))
            q.and(
                    w -> {
                        w.apply(
                                "LOCATE({0},COALESCE(snap.department_name,''))>0",
                                f.departmentName());
                        if (!departmentEmployeeCodes.isEmpty())
                            w.or(
                                    fallback ->
                                            fallback.isNull("snap.department_id")
                                                    .apply(
                                                            "NULLIF(TRIM(snap.department_name),'')"
                                                                + " IS NULL")
                                                    .in(
                                                            "COALESCE(NULLIF(snap.employee_code,''),o.owner_employee_code)",
                                                            departmentEmployeeCodes));
                    });
        q.like(text(f.partyName()), "o.customer_name_snapshot", f.partyName());
        q.ge(
                f.dateFrom() != null,
                "o.order_date",
                f.dateFrom() == null ? null : f.dateFrom().atStartOfDay().minusHours(8));
        q.lt(
                f.dateTo() != null,
                "o.order_date",
                f.dateTo() == null ? null : f.dateTo().plusDays(1).atStartOfDay().minusHours(8));
        return mapper.deliveries(
                q.orderByDesc("o.id").last("LIMIT 50 OFFSET " + Math.max(0, f.begin())));
    }

    public List<OrderDeliveryCostSourceView> salesByNos(
            String tenant, List<String> documentNos, String action, boolean historical) {
        if (documentNos.isEmpty()) return List.of();
        var q =
                new QueryWrapper<Object>()
                        .eq("o.tenant_id", tenant)
                        .eq("o.deleted", 0)
                        .notIn(!historical, "o.order_status_code", "DRAFT", "CANCELLED", "DELETED")
                        .in("o.order_no", documentNos);
        var predicate = scope.predicate(action, "o.selected_warehouse_id");
        MybatisPlusOrderLogisticsCostRepository.bind(q, predicate.sql(), predicate.args());
        scope.applyCity(q, action, "o.region_code");
        return mapper.deliveries(q.orderByAsc("o.order_no"));
    }

    private static boolean text(String v) {
        return v != null && !v.isBlank();
    }
}
