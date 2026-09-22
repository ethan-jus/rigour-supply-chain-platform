package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

/** 业务页面独立同步入口；一项请求只执行一种来源对象。 */
public interface DhbPageSyncApi {
    @PostMapping("/api/v1/integration/dhb/page-sync")
    ApiResponse<DhbSyncOrchestrationResult> syncPage(@RequestBody DhbPageSyncCommand command);

    @PostMapping("/api/v1/integration/dhb/page-sync/jobs/{requestId}")
    ApiResponse<DhbPageSyncJob> startJob(@PathVariable("requestId") java.util.UUID requestId,
            @RequestBody DhbPageSyncCommand command);

    @GetMapping("/api/v1/integration/dhb/page-sync/jobs/{jobId}")
    ApiResponse<DhbPageSyncJob> job(@PathVariable("jobId") java.util.UUID jobId);

    @GetMapping("/api/v1/integration/dhb/page-sync/jobs")
    ApiResponse<DhbPageSyncJob> latestJob(@RequestParam("connectorId") java.util.UUID connectorId,
            @RequestParam("scope") String scope);
}
