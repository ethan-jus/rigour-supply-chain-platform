package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsReceivablesApi;
import com.rigour.analytics.api.v1.model.ReceivablesView;
import com.rigour.analytics.application.service.ReceivablesService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class AnalyticsReceivablesController implements AnalyticsReceivablesApi {
    private final ReceivablesService service;

    public AnalyticsReceivablesController(ReceivablesService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<ReceivablesView> receivables(
            LocalDate from,
            LocalDate asOf,
            String regionCode,
            String ownerStaffCode,
            String customerId,
            String aging,
            String collector,
            Boolean disputed,
            int page,
            int size) {
        return ApiResponse.success(
                service.loadFiltered(
                        from,
                        asOf,
                        regionCode,
                        ownerStaffCode,
                        customerId,
                        aging,
                        page,
                        size,
                        collector,
                        disputed));
    }
}
