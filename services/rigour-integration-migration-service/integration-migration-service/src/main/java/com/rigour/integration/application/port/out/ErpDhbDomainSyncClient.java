package com.rigour.integration.application.port.out;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.shared.context.CallerIdentity;
import java.util.UUID;

/** Integration 编排器触发 ERP 领域服务同步的端口。 */
public interface ErpDhbDomainSyncClient {
    ErpDataSyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                           String objectType, int maxPages);
}
