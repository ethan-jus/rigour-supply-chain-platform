package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsBiComparisonApi;
import com.rigour.analytics.api.v1.model.BiComparisonView;
import com.rigour.analytics.application.service.BiComparisonService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** 只读经营比较接口适配器。 */
@RestController
public final class AnalyticsBiComparisonController implements AnalyticsBiComparisonApi {
    private final BiComparisonService service;
    public AnalyticsBiComparisonController(BiComparisonService service) { this.service = service; }
    @Override
    public ApiResponse<BiComparisonView> comparison(Instant from, Instant to, String regionCode,
            String ownerStaffCode, String customerTypeCode, Long productCategoryId, String sourceSystemCode) {
        return ApiResponse.success(service.compare(from, to, regionCode, ownerStaffCode,
                customerTypeCode, productCategoryId, sourceSystemCode));
    }
}
