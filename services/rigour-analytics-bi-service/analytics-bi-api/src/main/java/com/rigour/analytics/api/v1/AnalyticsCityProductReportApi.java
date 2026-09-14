package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.CityProductReportView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 城市商品报表独立只读契约；回款取所选下单期间订单的累计金额。 */
public interface AnalyticsCityProductReportApi {
    String PATH = "/api/v1/analytics/supply/dashboard/city-product-report";

    @GetMapping(PATH)
    ApiResponse<CityProductReportView> cityProductReport(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode,
            @RequestParam(defaultValue = "EXACT_ONLY") String allocationMode,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long skuId);
}
