package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;

import java.time.LocalDate;
import java.util.List;

public interface OrderCostSourceStore {
    default List<com.rigour.order.api.v1.model.OrderCityCollectionSourceView> cityCollections(
            String tenant, String city, LocalDate from, LocalDate to) {
        throw new UnsupportedOperationException("城市到账来源尚未接入");
    }

    List<OrderCollectionSourceView> collections(
            String tenant, String employee, LocalDate from, LocalDate to);

    List<OrderDeliveryCostSourceView> deliveries(String tenant, String keyword, List<Long> ids);

    List<OrderCommissionSourceLineView> commissions(
            String tenant, String employeeCode, String cityCode, LocalDate through);

    default List<com.rigour.order.api.v1.model.OrderPayrollLineView> payrollLines(
            String tenant, String employeeCode, String cityCode, LocalDate through) {
        throw new UnsupportedOperationException("整单付清来源尚未接入");
    }

    default List<OrderDeliveryCostSourceView> deliveries(
            String tenant, String keyword, List<Long> ids, String city, int begin, String action) {
        return deliveries(tenant, keyword, ids);
    }
}
