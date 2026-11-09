package com.rigour.order.api;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.application.service.cost.LogisticsImportService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders/logistics-costs/imports")
public final class LogisticsImportController {
    private final LogisticsImportService service;

    public LogisticsImportController(LogisticsImportService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Row>> rows(@RequestParam(defaultValue = "0") int begin) {
        return ApiResponse.success(service.rows(begin));
    }

    @PostMapping
    public ApiResponse<List<Row>> stage(@RequestBody Upload upload) {
        return ApiResponse.success(service.stage(upload));
    }

    @PostMapping("/{id}/generate")
    public ApiResponse<LogisticsCostDetailView> generate(
            @PathVariable String id, @RequestBody Generated command) {
        return ApiResponse.success(service.generate(id, command));
    }
}
