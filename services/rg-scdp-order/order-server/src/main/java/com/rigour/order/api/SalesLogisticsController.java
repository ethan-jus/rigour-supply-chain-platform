package com.rigour.order.api;

import com.rigour.order.api.v1.model.SalesLogisticsModels.*;
import com.rigour.order.api.v1.model.TransportRecordModels.Detail;
import com.rigour.order.application.service.cost.SalesLogisticsService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/orders/sales-logistics")
public class SalesLogisticsController {
    private final SalesLogisticsService service;

    public SalesLogisticsController(SalesLogisticsService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ApiResponse<Context> context(
            @PathVariable Long id, @RequestParam(defaultValue = "false") boolean forSelection) {
        return ApiResponse.success(service.context(id, forSelection));
    }

    @PostMapping("/counts")
    public ApiResponse<Map<String, Integer>> counts(@RequestBody List<String> nos) {
        return ApiResponse.success(service.counts(nos));
    }

    @PostMapping("/{id}/link")
    public ApiResponse<Detail> link(@PathVariable Long id, @RequestBody Link command) {
        return ApiResponse.success(service.link(id, command));
    }
}
