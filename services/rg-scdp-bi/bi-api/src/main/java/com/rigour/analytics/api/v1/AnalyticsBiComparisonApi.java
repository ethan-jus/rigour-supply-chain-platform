package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.BiComparisonView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 经营前期比较 HTTP 契约；不支持把商品行口径混入整单总额。 */
public interface AnalyticsBiComparisonApi {
    @GetMapping("/api/v1/analytics/supply/dashboard/comparison")
    ApiResponse<BiComparisonView> comparison(
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode);
}
