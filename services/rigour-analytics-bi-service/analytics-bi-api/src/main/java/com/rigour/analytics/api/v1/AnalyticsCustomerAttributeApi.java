package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.CustomerAttributeAnalyticsView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** CRM 客户档案属性统计；仅按授权城市/员工和订单期间读取 BI 本地投影。 */
public interface AnalyticsCustomerAttributeApi {
    @GetMapping("/api/v1/analytics/supply/dashboard/customer-attributes")
    ApiResponse<CustomerAttributeAnalyticsView> customerAttributes(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode);
}
