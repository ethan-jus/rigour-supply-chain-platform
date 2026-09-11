package com.rigour.integration.application.port.out;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.UUID;

/** Integration 编排器触发 ERP 领域服务同步的端口。 */
public interface ErpDhbDomainSyncClient {
    default ErpDataSyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                                   String objectType, int maxPages) {
        return sync(caller, connectorId, sourceTaskId, objectType, maxPages, null, null);
    }

    default ErpDataSyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                                   String objectType, int maxPages, String triggerType,
                                   Instant from, Instant to) {
        return sync(caller, connectorId, sourceTaskId, objectType, maxPages, from, to);
    }

    ErpDataSyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                           String objectType, int maxPages, Instant from, Instant to);
}
