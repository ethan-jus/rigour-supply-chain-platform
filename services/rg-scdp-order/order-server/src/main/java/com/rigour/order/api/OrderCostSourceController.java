package com.rigour.order.api;

import com.rigour.order.api.v1.OrderCostSourceApi;
import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.order.api.v1.model.OrderDeliveryCostSourceView;
import com.rigour.order.application.service.OrderCostSourceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public final class OrderCostSourceController implements OrderCostSourceApi {
    @Override
    public ApiResponse<List<OrderCollectionSourceView>> collections(
            String employeeCode, LocalDate from, LocalDate to) {
        return ApiResponse.success(service.collections(employeeCode, from, to));
    }

    private final OrderCostSourceService service;

    public OrderCostSourceController(OrderCostSourceService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<List<OrderDeliveryCostSourceView>> deliveries(
            String keyword, List<Long> ids) {
        return ApiResponse.success(service.deliveries(keyword, ids));
    }

    @Override
    public ApiResponse<List<OrderCommissionSourceLineView>> commissions(
            String employeeCode, String cityCode, LocalDate through) {
        return ApiResponse.success(service.commissions(employeeCode, cityCode, through));
    }
}
