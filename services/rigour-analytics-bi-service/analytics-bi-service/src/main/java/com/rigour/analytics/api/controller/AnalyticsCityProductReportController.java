package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsCityProductReportApi;
import com.rigour.analytics.api.v1.model.CityProductReportView;
import com.rigour.analytics.application.service.CityProductReportService;
import com.rigour.analytics.application.service.BiDataScopeService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** 城市商品报表独立 HTTP 入口，不修改现有看板控制器。 */
@RestController
public final class AnalyticsCityProductReportController implements AnalyticsCityProductReportApi {
    private final CityProductReportService service;
    private final BiDataScopeService scopes;

    public AnalyticsCityProductReportController(CityProductReportService service, BiDataScopeService scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @Override
    public ApiResponse<CityProductReportView> cityProductReport(
            Instant from, Instant to, String regionCode, String ownerStaffCode, String customerTypeCode,
            Long productCategoryId, String sourceSystemCode, String allocationMode,
            Long brandId, Long productId, Long skuId) {
        var scope = scopes.resolve(regionCode, ownerStaffCode);
        Instant scopedTo = to == null && !scope.fullTenant() ? Instant.now() : to;
        return ApiResponse.success(service.report(from, scopedTo, scope.regionCode(), scope.ownerStaffCode(), customerTypeCode,
                productCategoryId, sourceSystemCode, allocationMode, brandId, productId, skuId));
    }
}
