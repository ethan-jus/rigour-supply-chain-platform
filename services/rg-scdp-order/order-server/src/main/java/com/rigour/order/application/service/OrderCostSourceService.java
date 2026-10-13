package com.rigour.order.application.service;

import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;
import com.rigour.order.application.port.out.OrderCostSourceStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public final class OrderCostSourceService {
    public List<OrderCollectionSourceView> collections(
            String employee, LocalDate from, LocalDate to) {
        String tenant = tenant("order:payroll:source-read");
        if (employee == null
                || employee.isBlank()
                || from == null
                || to == null
                || to.isBefore(from)
                || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 31)
            throw new IllegalArgumentException("核算期间无效");
        return store.collections(tenant, employee, from, to);
    }

    private final OrderCostSourceStore store;

    public OrderCostSourceService(OrderCostSourceStore store) {
        this.store = store;
    }

    private String tenant(String permission) {
        var a = AuthorizationContext.requireCurrent();
        if (a.tenantId() == null || !"SERVICE".equals(a.principalScope()))
            throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        return a.tenantId().toString();
    }

    public List<OrderDeliveryCostSourceView> deliveries(String keyword, List<Long> ids) {
        if (ids != null && ids.size() > 100) throw new IllegalArgumentException("最多100张来源单据");
        return store.deliveries(
                tenant("order:cost:source-read"), keyword, ids == null ? List.of() : ids);
    }

    public List<OrderCommissionSourceLineView> commissions(
            String employeeCode, String cityCode, LocalDate through) {
        if (((employeeCode == null || employeeCode.isBlank())
                        == (cityCode == null || cityCode.isBlank()))
                || through == null) throw new IllegalArgumentException("请选择员工或城市之一，并提供核算日期");
        return store.commissions(
                tenant("order:payroll:source-read"), employeeCode, cityCode, through);
    }
}
