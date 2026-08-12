package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpDataSyncApi;
import com.rigour.erp.api.v1.model.ErpDataSyncCommand;
import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.service.sync.ErpDataSyncService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 手动同步的唯一浏览器边界。 */
@RestController
public final class ErpDataSyncController implements ErpDataSyncApi {
    private final ErpDataSyncService service;

    public ErpDataSyncController(ErpDataSyncService service) {
        this.service = service;
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<ErpDataSyncResult> sync(ErpDataSyncCommand command) {
        return ApiResponse.success(service.run(command));
    }
}
