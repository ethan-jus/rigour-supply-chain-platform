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
    public ApiResponse<List<com.rigour.order.api.v1.model.OrderCityCollectionSourceView>>
            cityCollections(String cityCode, LocalDate from, LocalDate to) {
        return ApiResponse.success(service.cityCollections(cityCode, from, to));
    }

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
            String keyword, List<Long> ids, String cityCode, int begin, String action) {
        return ApiResponse.success(service.deliveries(keyword, ids, cityCode, begin, action));
    }

    @Override
    public ApiResponse<List<OrderCommissionSourceLineView>> commissions(
            String employeeCode, String cityCode, LocalDate through) {
        return ApiResponse.success(service.commissions(employeeCode, cityCode, through));
    }

    @Override
    public ApiResponse<List<com.rigour.order.api.v1.model.OrderPayrollLineView>> payrollLines(
            String employeeCode, String cityCode, LocalDate through) {
        return ApiResponse.success(service.payrollLines(employeeCode, cityCode, through));
    }
}
