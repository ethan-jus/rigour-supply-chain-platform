package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsVisitApi;
import com.rigour.analytics.api.v1.model.VisitAnalyticsView;
import com.rigour.analytics.application.service.VisitAnalyticsService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** 销售拜访看板 HTTP 入口；不承载聚合或身份推断。 */
@RestController
public final class AnalyticsVisitController implements AnalyticsVisitApi {
    private final VisitAnalyticsService service;
    public AnalyticsVisitController(VisitAnalyticsService service) { this.service = service; }
    @Override public ApiResponse<VisitAnalyticsView> visits(Instant from, Instant to, String regionCode, String ownerStaffCode) {
        return ApiResponse.success(service.report(from, to, regionCode, ownerStaffCode));
    }
}
