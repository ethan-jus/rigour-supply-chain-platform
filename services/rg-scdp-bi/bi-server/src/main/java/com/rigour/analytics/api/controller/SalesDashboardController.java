package com.rigour.analytics.api.controller;

import com.rigour.analytics.application.model.SalesDashboardData;
import com.rigour.analytics.application.model.SalesDashboardSummary;
import com.rigour.analytics.application.port.out.SalesDashboardStore.Projection;
import java.util.List;
import com.rigour.analytics.application.service.SalesDashboardService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
public class SalesDashboardController {
    private final SalesDashboardService service;

    public SalesDashboardController(SalesDashboardService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/analytics/supply/dashboard/sales-analysis")
    public ApiResponse<SalesDashboardData> query(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode,
            @RequestParam(defaultValue = "FULL") Projection projection) {
        return ApiResponse.success(
                service.query(
                        from,
                        to,
                        regionCode,
                        ownerStaffCode,
                        customerTypeCode,
                        productCategoryId,
                        sourceSystemCode,
                        projection));
    }

    public record Selection(List<String> ownerStaffCodes) {}

    @PostMapping("/api/v1/analytics/supply/dashboard/sales-analysis/summary")
    public ApiResponse<SalesDashboardSummary> summary(
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode,
            @RequestBody Selection selection) {
        return ApiResponse.success(service.summary(from, to, regionCode, ownerStaffCode,
                customerTypeCode, productCategoryId, sourceSystemCode, selection.ownerStaffCodes()));
    }
}
