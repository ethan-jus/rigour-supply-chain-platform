package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;

import java.time.LocalDate;
import java.util.List;

public interface OrderCostSourceStore {
    List<OrderCollectionSourceView> collections(
            String tenant, String employee, LocalDate from, LocalDate to);

    List<OrderDeliveryCostSourceView> deliveries(String tenant, String keyword, List<Long> ids);

    List<OrderCommissionSourceLineView> commissions(
            String tenant, String employeeCode, String cityCode, LocalDate through);
}
