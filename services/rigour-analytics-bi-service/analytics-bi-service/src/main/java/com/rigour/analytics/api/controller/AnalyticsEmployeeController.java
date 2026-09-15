package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsEmployeeApi;
import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView;
import com.rigour.analytics.application.service.EmployeeAnalyticsService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** 员工分析 HTTP 入口；权限和筛选由应用服务统一收紧。 */
@RestController
public final class AnalyticsEmployeeController implements AnalyticsEmployeeApi {
    private final EmployeeAnalyticsService service;
    public AnalyticsEmployeeController(EmployeeAnalyticsService service) { this.service = service; }
    @Override public ApiResponse<EmployeeAnalyticsView> employees(Instant from, Instant to, String regionCode, String ownerStaffCode) {
        return ApiResponse.success(service.report(from, to, regionCode, ownerStaffCode));
    }
}
