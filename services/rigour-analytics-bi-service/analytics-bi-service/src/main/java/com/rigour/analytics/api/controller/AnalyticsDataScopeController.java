package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsDataScopeApi;
import com.rigour.analytics.api.v1.model.BiEffectiveScopeView;
import com.rigour.analytics.api.v1.model.BiScopeSyncCommand;
import com.rigour.analytics.api.v1.model.BiScopeSyncResultView;
import com.rigour.analytics.application.service.BiDataScopeService;
import com.rigour.analytics.application.service.BiDataScopeSyncService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

/** 数据范围只读 HTTP 边界，不提供用户自行扩大范围的授权入口。 */
@RestController
public final class AnalyticsDataScopeController implements AnalyticsDataScopeApi {
    private final BiDataScopeService scopes;
    private final BiDataScopeSyncService sync;
    public AnalyticsDataScopeController(BiDataScopeService scopes, BiDataScopeSyncService sync) { this.scopes = scopes; this.sync = sync; }
    @Override public ApiResponse<BiEffectiveScopeView> effectiveScope() {
        return ApiResponse.success(scopes.effective());
    }
    @Override public ApiResponse<BiScopeSyncResultView> synchronize(BiScopeSyncCommand command) {
        return ApiResponse.success(sync.synchronize(command));
    }
    @Override public ApiResponse<Void> revoke(UUID userId) {
        sync.revoke(userId);
        return ApiResponse.success(null);
    }
}
