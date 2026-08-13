package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CrmSyncApi;
import com.rigour.merchant.api.v1.model.SyncCommand;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.merchant.application.service.CrmMasterDataSyncService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** CRM 手动同步 HTTP 边界。 */
@RestController
public final class CrmSyncController implements CrmSyncApi {
    private final CrmMasterDataSyncService service;

    public CrmSyncController(CrmMasterDataSyncService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<SyncResult> sync(SyncCommand command) {
        return ApiResponse.success(service.run(command));
    }
}
