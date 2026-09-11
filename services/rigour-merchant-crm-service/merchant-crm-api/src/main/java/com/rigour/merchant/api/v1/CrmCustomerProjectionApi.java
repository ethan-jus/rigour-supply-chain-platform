package com.rigour.merchant.api.v1;

import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncResult;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** CRM 外部来源投影契约；仅供 Integration 服务间调用。 */
public interface CrmCustomerProjectionApi {
    String BASE_PATH = "/internal/v1/crm/customers";

    @PostMapping(BASE_PATH + "/external-sync")
    ApiResponse<ExternalCrmCustomerSyncResult> syncExternalCustomers(
            @RequestBody ExternalCrmCustomerSyncCommand command);

    @PostMapping(BASE_PATH + "/areas/external-sync")
    ApiResponse<ExternalCrmAreaSyncResult> syncExternalCustomerAreas(
            @RequestBody ExternalCrmAreaSyncCommand command);
}
