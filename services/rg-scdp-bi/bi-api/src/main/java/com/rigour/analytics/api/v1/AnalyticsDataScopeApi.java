package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.BiEffectiveScopeView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;

/** 当前用户的有效 BI 数据范围与默认查询条件。 */
public interface AnalyticsDataScopeApi {
    @GetMapping("/api/v1/analytics/supply-dashboard/effective-scope")
    ApiResponse<BiEffectiveScopeView> effectiveScope();

}
