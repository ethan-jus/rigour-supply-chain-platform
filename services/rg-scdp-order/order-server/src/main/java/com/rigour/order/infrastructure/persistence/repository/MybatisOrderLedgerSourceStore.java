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
    public MybatisOrderLedgerSourceStore(OrderCostSourceMapper mapper, LogisticsWarehouseDataScope scope) { this.mapper = mapper; this.scope = scope; }
    public List<OrderDeliveryCostSourceView> sales(String tenant, LogisticsLedgerSourceQuery f, String action, Long warehouseId) {
        var q = new QueryWrapper<Object>().eq("o.tenant_id", tenant).eq("o.deleted", 0).notIn(!f.historical(), "o.order_status_code", "DRAFT", "CANCELLED");
        var p = scope.predicate(action, "o.selected_warehouse_id");
        MybatisPlusOrderLogisticsCostRepository.bind(q, p.sql(), p.args());
        scope.applyCity(q, action, "o.region_code");
        q.eq(text(f.cityCode()), "o.region_code", f.cityCode());
        q.eq(warehouseId != null, "o.selected_warehouse_id", warehouseId);
        if (text(f.documentNo())) { if (f.exact()) q.eq("o.order_no", f.documentNo()); else q.like("o.order_no", f.documentNo()); }
        q.like(text(f.partyName()), "o.customer_name_snapshot", f.partyName());
        q.ge(f.dateFrom() != null, "o.order_date", f.dateFrom() == null ? null : f.dateFrom().atStartOfDay().minusHours(8));
        q.lt(f.dateTo() != null, "o.order_date", f.dateTo() == null ? null : f.dateTo().plusDays(1).atStartOfDay().minusHours(8));
        return mapper.deliveries(q.orderByDesc("o.id").last("LIMIT 50 OFFSET " + Math.max(0, f.begin())));
    }
    private static boolean text(String v) { return v != null && !v.isBlank(); }
}
