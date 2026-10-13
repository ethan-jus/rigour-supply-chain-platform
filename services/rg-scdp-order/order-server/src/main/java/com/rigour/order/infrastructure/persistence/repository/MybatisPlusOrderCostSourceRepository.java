package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;
import com.rigour.order.application.port.out.OrderCostSourceStore;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;
import com.rigour.order.infrastructure.persistence.mapper.OrderCostSourceMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public class MybatisPlusOrderCostSourceRepository implements OrderCostSourceStore {
    private final OrderCostSourceMapper mapper;

    public MybatisPlusOrderCostSourceRepository(OrderCostSourceMapper mapper) {
        this.mapper = mapper;
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
    public List<OrderDeliveryCostSourceView> deliveries(
            String tenant, String keyword, List<Long> ids) {
        var q =
                new QueryWrapper<InternalSalesOrderEntity>()
                        .eq("o.tenant_id", tenant)
                        .eq("o.deleted", 0)
                        .notIn("o.order_status_code", "DRAFT", "CANCELLED")
                        .isNotNull("o.selected_warehouse_id");
        if (ids.isEmpty())
            q.and(
                    w ->
                            w.like("o.order_no", keyword)
                                    .or()
                                    .like("o.customer_name_snapshot", keyword));
        else q.in("o.id", ids);
        return mapper.deliveries(q.orderByDesc("o.id").last("LIMIT 100"));
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
}
