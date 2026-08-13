package com.rigour.merchant.api.v1;

import com.rigour.merchant.api.v1.model.SyncCommand;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** CRM 订货宝主数据手动同步入口。 */
public interface CrmSyncApi {
    String SYNC_PATH = "/api/v1/crm/sync";

    @PostMapping(SYNC_PATH)
    ApiResponse<SyncResult> sync(@RequestBody(required = false) SyncCommand command);
}
