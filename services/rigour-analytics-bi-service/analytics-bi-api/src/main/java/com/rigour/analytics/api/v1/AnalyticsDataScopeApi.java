package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.BiEffectiveScopeView;
import com.rigour.analytics.api.v1.model.BiScopeSyncCommand;
import com.rigour.analytics.api.v1.model.BiScopeSyncResultView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;

/** 当前用户的有效 BI 数据范围与默认查询条件。 */
public interface AnalyticsDataScopeApi {
    @GetMapping("/api/v1/analytics/supply-dashboard/effective-scope")
    ApiResponse<BiEffectiveScopeView> effectiveScope();

    @PostMapping("/api/v1/analytics/supply-dashboard/data-scopes/synchronize")
    ApiResponse<BiScopeSyncResultView> synchronize(@RequestBody BiScopeSyncCommand command);

    @DeleteMapping("/api/v1/analytics/supply-dashboard/data-scopes/{userId}")
    ApiResponse<Void> revoke(@PathVariable("userId") UUID userId);
}
