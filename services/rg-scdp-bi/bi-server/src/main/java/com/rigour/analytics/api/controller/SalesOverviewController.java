package com.rigour.analytics.api.controller;

import com.rigour.analytics.application.model.SalesOverview;
import com.rigour.analytics.application.model.SalesOverview.DetailKind;
import com.rigour.analytics.application.model.SalesOverview.DetailPage;
import com.rigour.analytics.application.service.SalesOverviewService;
import com.rigour.analytics.application.service.SalesOverviewService.Query;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics/supply/dashboard/sales-overview")
public class SalesOverviewController {
    private final SalesOverviewService service;

    public SalesOverviewController(SalesOverviewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<SalesOverview> overview(@ModelAttribute Query query) {
        return ApiResponse.success(service.overview(query));
    }

    @GetMapping("/details")
    public ApiResponse<DetailPage> details(
            @ModelAttribute Query query,
            @RequestParam DetailKind kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.details(query, kind, page, size));
    }
}
