package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.VisitAnalyticsView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Sales 已提交拜访看板契约；沿用 BI 读取权限和可信员工、城市范围。 */
public interface AnalyticsVisitApi {
    @GetMapping("/api/v1/analytics/supply/dashboard/visits")
    ApiResponse<VisitAnalyticsView> visits(@RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode);
}
