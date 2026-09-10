package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CrmCustomerProjectionApi;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
import com.rigour.merchant.application.service.CrmCustomerQueryService;
import com.rigour.merchant.application.service.CrmInternalCustomerService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** CRM 外部客户/门店投影 HTTP 边界。 */
@RestController
public final class CrmCustomerProjectionController implements CrmCustomerProjectionApi {
    private final CrmInternalCustomerService service;
    private final CrmCustomerQueryService queryService;

    public CrmCustomerProjectionController(CrmInternalCustomerService service,
                                           CrmCustomerQueryService queryService) {
        this.service = service;
        this.queryService = queryService;
    }

    @Override
    public ApiResponse<ExternalCrmCustomerSyncResult> syncExternalCustomers(
            ExternalCrmCustomerSyncCommand command) {
        return ApiResponse.success(service.syncExternalCustomers(command));
    }

    @Override
    public ApiResponse<ExternalCrmAreaSyncResult> syncExternalCustomerAreas(
            ExternalCrmAreaSyncCommand command) {
        return ApiResponse.success(queryService.syncExternalCustomerAreas(command));
    }
}
