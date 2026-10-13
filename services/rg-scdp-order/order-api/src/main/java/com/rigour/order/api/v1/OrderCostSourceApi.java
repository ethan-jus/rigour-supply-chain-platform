package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

public interface OrderCostSourceApi {
    @GetMapping("/api/v1/orders/cost-sources/collections")
    ApiResponse<List<OrderCollectionSourceView>> collections(
            @RequestParam String employeeCode,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to);

    @GetMapping("/api/v1/orders/cost-sources/deliveries")
    ApiResponse<List<OrderDeliveryCostSourceView>> deliveries(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) List<Long> ids);

    @GetMapping("/api/v1/orders/cost-sources/commissions")
    ApiResponse<List<OrderCommissionSourceLineView>> commissions(
            @RequestParam(required = false) String employeeCode,
            @RequestParam(required = false) String cityCode,
            @RequestParam LocalDate through);
}
