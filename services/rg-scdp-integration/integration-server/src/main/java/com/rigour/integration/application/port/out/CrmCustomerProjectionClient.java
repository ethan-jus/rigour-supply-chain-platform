package com.rigour.integration.application.port.out;

import com.rigour.merchant.api.v1.model.ExternalCrmAreaRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** Integration 向 CRM 投影外部区域、客户/门店主数据的出站端口。 */
public interface CrmCustomerProjectionClient {
    ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                        List<ExternalCrmAreaRowCommand> rows);

    ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                       List<ExternalCrmCustomerRowCommand> rows);
}
