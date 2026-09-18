package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsOperatingWorkspaceApi;
import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.analytics.application.service.OperatingWorkspaceService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/** 目标与跟进 HTTP 适配；授权、校验和状态变化由应用用例负责。 */
@RestController
public final class AnalyticsOperatingWorkspaceController implements AnalyticsOperatingWorkspaceApi {
    private final OperatingWorkspaceService service;
    public AnalyticsOperatingWorkspaceController(OperatingWorkspaceService service) { this.service = service; }
    @Override public ApiResponse<List<TargetView>> targets(String month, String type, String code) {
        return ApiResponse.success(service.targets(month, type, code));
    }
    @Override public ApiResponse<TargetView> saveTarget(TargetCommand command) { return ApiResponse.success(service.saveTarget(command)); }
    @Override public ApiResponse<Void> deleteTarget(String id, int revision) {
        service.deleteTarget(id, revision); return ApiResponse.success(null);
    }
    @Override public ApiResponse<ActionPage> actions(String kind, String businessRef, String cityCode,
            String employeeCode, String assignee, String status, int page, int pageSize) {
        return ApiResponse.success(service.actions(kind, businessRef, cityCode, employeeCode, assignee, status, page, pageSize));
    }
    @Override public ApiResponse<ActionView> createAction(ActionCommand command) { return ApiResponse.success(service.createAction(command)); }
    @Override public ApiResponse<ActionView> updateAction(String id, ActionUpdateCommand command) {
        return ApiResponse.success(service.updateAction(id, command));
    }
    @Override public ApiResponse<List<ActionEventView>> actionEvents(String id) { return ApiResponse.success(service.events(id)); }
}
