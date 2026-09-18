package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderParameterApi;
import com.rigour.order.application.service.sales.OrderParameterService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class OrderParameterController implements OrderParameterApi {
    private final OrderParameterService service;

    public OrderParameterController(OrderParameterService service) {
        this.service = service;
    }

    public ApiResponse<List<Parameter>> list() {
        return ApiResponse.success(service.list());
    }

    public ApiResponse<Parameter> save(String code, Change command) {
        return ApiResponse.success(service.save(code, command));
    }
}
