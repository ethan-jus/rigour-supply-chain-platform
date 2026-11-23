package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.ReceivablesView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

public interface AnalyticsReceivablesApi {
    @GetMapping("/api/v1/analytics/supply/dashboard/receivables")
    ApiResponse<ReceivablesView> receivables(
            @RequestParam LocalDate from,
            @RequestParam LocalDate asOf,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "false") boolean includeSubDepartments,
            @RequestParam(required = false) String aging,
            @RequestParam(required = false) String collector,
            @RequestParam(required = false) Boolean disputed,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size);
}
