package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbPageSyncApi;
import com.rigour.integration.api.v1.model.*;
import com.rigour.integration.application.service.dhb.DhbSyncOrchestrationService;
import com.rigour.integration.application.service.dhb.DhbPageSyncJobService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

/** 权限由同步应用服务校验，不允许页面省略范围触发全部同步。 */
@RestController
public final class DhbPageSyncController implements DhbPageSyncApi {
    private final DhbSyncOrchestrationService service;
    private final DhbPageSyncJobService jobs;

    public DhbPageSyncController(DhbSyncOrchestrationService service, DhbPageSyncJobService jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    public ApiResponse<DhbSyncOrchestrationResult> syncPage(DhbPageSyncCommand command) {
        return ApiResponse.success(service.runPage(AuthorizationContext.requireCurrent(), command));
    }
    public ApiResponse<DhbPageSyncJob> startJob(java.util.UUID requestId, DhbPageSyncCommand command) {
        return ApiResponse.success(jobs.start(AuthorizationContext.requireCurrent(), requestId, command));
    }
    public ApiResponse<DhbPageSyncJob> job(java.util.UUID jobId) {
        return ApiResponse.success(jobs.get(AuthorizationContext.requireCurrent(), jobId));
    }
    public ApiResponse<DhbPageSyncJob> latestJob(java.util.UUID connectorId, String scope) {
        return ApiResponse.success(jobs.latest(AuthorizationContext.requireCurrent(), connectorId, scope));
    }
}
