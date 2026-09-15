package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsCustomerAttributeApi;
import com.rigour.analytics.api.v1.model.CustomerAttributeAnalyticsView;
import com.rigour.analytics.application.service.CustomerAttributeAnalyticsService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** 客户来源/经营类别 HTTP 入口；范围校验交给应用服务。 */
@RestController
public class AnalyticsCustomerAttributeController implements AnalyticsCustomerAttributeApi {
    private final CustomerAttributeAnalyticsService service;
    public AnalyticsCustomerAttributeController(CustomerAttributeAnalyticsService service) { this.service = service; }
    @Override public ApiResponse<CustomerAttributeAnalyticsView> customerAttributes(
            Instant from, Instant to, String regionCode, String ownerStaffCode) {
        return ApiResponse.success(service.report(from, to, regionCode, ownerStaffCode));
    }
}
