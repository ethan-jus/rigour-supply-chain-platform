package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbSyncOrchestrationApi;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationCommand;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationResult;
import com.rigour.integration.application.service.dhb.DhbSyncOrchestrationService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** Portal 同步中心的统一手动同步入口。 */
@RestController
public final class DhbSyncOrchestrationController implements DhbSyncOrchestrationApi {
    private final DhbSyncOrchestrationService service;

    public DhbSyncOrchestrationController(DhbSyncOrchestrationService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<DhbSyncOrchestrationResult> sync(DhbSyncOrchestrationCommand command) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.runManual(caller, command));
    }
}
