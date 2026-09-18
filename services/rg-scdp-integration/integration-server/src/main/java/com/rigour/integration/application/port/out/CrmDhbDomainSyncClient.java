package com.rigour.integration.application.port.out;

import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.UUID;

/** Integration 编排器触发 CRM 领域服务同步的端口。 */
public interface CrmDhbDomainSyncClient {
    default SyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                            int maxPages) {
        return sync(caller, connectorId, sourceTaskId, maxPages, null, null);
    }

    default SyncResult syncObject(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
            String objectType, int maxPages, Instant from, Instant to) {
        throw new UnsupportedOperationException("CRM客户端未支持独立对象同步");
    }

    default SyncResult syncLatestCustomers(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
            int maxPages) {
        throw new UnsupportedOperationException("CRM客户端未支持客户增量同步");
    }

    default SyncResult syncLatestCustomers(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
            int maxPages, UUID initiatedBy) {
        return syncLatestCustomers(caller, connectorId, sourceTaskId, maxPages);
    }

    SyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                    int maxPages, Instant from, Instant to);
}
