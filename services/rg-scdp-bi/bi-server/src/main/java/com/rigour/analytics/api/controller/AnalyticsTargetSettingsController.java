package com.rigour.analytics.api.controller;
import com.rigour.analytics.api.v1.AnalyticsTargetSettingsApi;
import com.rigour.analytics.api.v1.model.TargetSettingsModels.*;
import com.rigour.analytics.application.service.TargetSettingsService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
@RestController
public class AnalyticsTargetSettingsController implements AnalyticsTargetSettingsApi {
    private final TargetSettingsService service;
    public AnalyticsTargetSettingsController(TargetSettingsService service) { this.service=service; }
    public ApiResponse<Settings> settings(String month) { return ApiResponse.success(service.settings(month)); }
    public ApiResponse<Void> save(Batch command) { service.save(command); return ApiResponse.success(null); }
    public ApiResponse<Void> defaults(DefaultsBatch command) { service.saveDefaults(command); return ApiResponse.success(null); }
    public ApiResponse<List<History>> history(String month,String dimensionType,String code) {
        return ApiResponse.success(service.history(month,dimensionType,code));
    }
}
