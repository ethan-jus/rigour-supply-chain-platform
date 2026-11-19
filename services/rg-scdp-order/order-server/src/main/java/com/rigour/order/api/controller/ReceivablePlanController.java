package com.rigour.order.api.controller;

import com.rigour.order.application.service.sales.ReceivablePlanService;
import com.rigour.order.application.service.sales.ReceivablePlanService.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders/sales/{id}/receivable-plan")
public class ReceivablePlanController {
    private final ReceivablePlanService service;

    public ReceivablePlanController(ReceivablePlanService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Plan> get(@PathVariable long id) {
        return ApiResponse.success(service.read(id));
    }

    @PutMapping
    public ApiResponse<Plan> put(@PathVariable long id, @RequestBody Command command) {
        return ApiResponse.success(service.save(id, command));
    }
}
