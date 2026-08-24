package com.rigour.integration.application.port.out;

import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.shared.context.CallerIdentity;
import java.util.UUID;

/** Integration 编排器触发 CRM 领域服务同步的端口。 */
public interface CrmDhbDomainSyncClient {
    SyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId, int maxPages);
}
