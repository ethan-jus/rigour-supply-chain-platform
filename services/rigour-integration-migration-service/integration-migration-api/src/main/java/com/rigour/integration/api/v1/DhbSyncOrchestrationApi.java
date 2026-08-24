package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DhbSyncOrchestrationCommand;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationResult;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 订货宝同步中心统一编排入口；Portal 只调用该入口发起手动同步。 */
public interface DhbSyncOrchestrationApi {
    String BASE_PATH = "/api/v1/integration/dhb/orchestration";
    String SYNC_PATH = BASE_PATH + "/sync";

    /** 按新业务表依赖顺序统一同步 ERP、CRM、Order。 */
    @PostMapping(SYNC_PATH)
    ApiResponse<DhbSyncOrchestrationResult> sync(
            @RequestBody(required = false) DhbSyncOrchestrationCommand command);
}
