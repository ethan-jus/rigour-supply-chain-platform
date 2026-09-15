package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 员工经营统计；沿用 BI 城市及本人数据范围，不输出手机号等个人联系方式。 */
public interface AnalyticsEmployeeApi {
    @GetMapping("/api/v1/analytics/supply/dashboard/employees")
    ApiResponse<EmployeeAnalyticsView> employees(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode);
}
