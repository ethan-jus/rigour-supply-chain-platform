package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsDataScopeApi;
import com.rigour.analytics.api.v1.model.BiEffectiveScopeView;
import com.rigour.analytics.application.service.BiDataScopeService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

/** 数据范围只读 HTTP 边界，不提供用户自行扩大范围的授权入口。 */
@RestController
public final class AnalyticsDataScopeController implements AnalyticsDataScopeApi {
    private final BiDataScopeService scopes;

    public AnalyticsDataScopeController(BiDataScopeService scopes) {
        this.scopes = scopes;
    }

    @Override
    public ApiResponse<BiEffectiveScopeView> effectiveScope() {
        return ApiResponse.success(scopes.effective());
    }
}
