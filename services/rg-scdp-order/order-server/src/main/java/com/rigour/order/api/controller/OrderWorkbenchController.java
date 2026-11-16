package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderWorkbenchApi;
import com.rigour.order.application.service.sales.OrderWorkbenchService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class OrderWorkbenchController implements OrderWorkbenchApi {
    private final OrderWorkbenchService service;
    public OrderWorkbenchController(OrderWorkbenchService service) { this.service = service; }
    @Override public ApiResponse<Performance> performance(String ownerEmployeeCode, LocalDate from, LocalDate to) {
        return ApiResponse.success(service.performance(ownerEmployeeCode, from, to));
    }
    @Override public ApiResponse<Summary> summary(String ownerEmployeeCode, LocalDate from, LocalDate to) {
        return ApiResponse.success(service.summary(ownerEmployeeCode, from, to));
    }
    @Override public ApiResponse<Page> details(String ownerEmployeeCode, String kind,
            LocalDate from, LocalDate to, int begin, int step) {
        return ApiResponse.success(service.details(ownerEmployeeCode, kind, from, to, begin, step));
    }
}
