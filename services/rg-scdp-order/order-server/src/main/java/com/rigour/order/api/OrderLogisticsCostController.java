package com.rigour.order.api;

import com.rigour.order.api.v1.OrderLogisticsCostApi;
import com.rigour.order.api.v1.model.LogisticsCostActionCommand;
import com.rigour.order.api.v1.model.LogisticsCostAuditView;
import com.rigour.order.api.v1.model.LogisticsCostCommand;
import com.rigour.order.api.v1.model.LogisticsCostDetailView;
import com.rigour.order.api.v1.model.LogisticsCostPageView;
import com.rigour.order.api.v1.model.LogisticsCostQuery;
import com.rigour.order.api.v1.model.LogisticsCostSourceView;
import com.rigour.order.application.service.cost.OrderLogisticsCostService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class OrderLogisticsCostController implements OrderLogisticsCostApi {
    private final OrderLogisticsCostService service;

    public OrderLogisticsCostController(OrderLogisticsCostService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<LogisticsCostPageView> entries(LogisticsCostQuery query) {
        return ApiResponse.success(service.entries("logistics", query));
    }

    @Override
    public ApiResponse<LogisticsCostPageView> export(LogisticsCostQuery query) {
        return ApiResponse.success(service.export("logistics", query));
    }

    @Override
    public ApiResponse<LogisticsCostDetailView> entry(String id) {
        return ApiResponse.success(service.entry("logistics", id));
    }

    @Override
    public ApiResponse<LogisticsCostDetailView> create(LogisticsCostCommand command) {
        return ApiResponse.success(service.save("logistics", null, command));
    }

    @Override
    public ApiResponse<LogisticsCostDetailView> update(String id, LogisticsCostCommand command) {
        return ApiResponse.success(service.save("logistics", id, command));
    }

    @Override
    public ApiResponse<LogisticsCostDetailView> confirm(
            String id, LogisticsCostActionCommand action) {
        return ApiResponse.success(service.action("logistics", id, action, true));
    }

    @Override
    public ApiResponse<LogisticsCostDetailView> voidEntry(
            String id, LogisticsCostActionCommand action) {
        return ApiResponse.success(service.action("logistics", id, action, false));
    }

    @Override
    public ApiResponse<List<LogisticsCostAuditView>> history(String id) {
        return ApiResponse.success(service.history("logistics", id));
    }

    @Override
    public ApiResponse<List<LogisticsCostSourceView>> sources(
            String legCode, String keyword, String cityCode, int begin) {
        return ApiResponse.success(service.sources(legCode, keyword, cityCode, begin));
    }

    @Override
    public ApiResponse<List<com.rigour.order.api.v1.model.LogisticsCostEmployeeView>> employees() {
        return ApiResponse.success(service.employees());
    }
}
