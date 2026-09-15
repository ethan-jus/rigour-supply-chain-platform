package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsCityContactApi;
import com.rigour.analytics.api.v1.model.CityContactAnalyticsView;
import com.rigour.analytics.application.service.CityContactAnalyticsService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** 城市建联 HTTP 入口，所有读取先通过 BI 数据范围校验。 */
@RestController
public final class AnalyticsCityContactController implements AnalyticsCityContactApi {
    private final CityContactAnalyticsService service;
    public AnalyticsCityContactController(CityContactAnalyticsService service) { this.service = service; }
    @Override public ApiResponse<CityContactAnalyticsView> cityContacts(Instant from, Instant to, String regionCode, String ownerStaffCode) {
        return ApiResponse.success(service.report(from, to, regionCode, ownerStaffCode));
    }
}
