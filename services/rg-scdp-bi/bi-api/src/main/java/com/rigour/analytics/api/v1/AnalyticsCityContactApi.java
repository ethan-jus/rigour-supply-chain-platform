package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.CityContactAnalyticsView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 所选期间内已提交拜访的 Sales 去重门店，不与 CRM 联系信息存量混合。 */
public interface AnalyticsCityContactApi {
    @GetMapping("/api/v1/analytics/supply/dashboard/city-contacts")
    ApiResponse<CityContactAnalyticsView> cityContacts(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode);
}
