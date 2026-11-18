package com.rigour.integration.application.port.out;

import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.UUID;

/** Integration 编排器触发 CRM 领域服务同步的端口。 */
public interface CrmDhbDomainSyncClient {
    default SyncResult syncLatestCustomersInBackground(CallerIdentity caller, UUID connectorId,
            UUID sourceTaskId, int maxPages, UUID initiatedBy, java.util.function.Consumer<String> progress) {
        throw new UnsupportedOperationException("CRM客户端尚未支持后台客户同步");
    }
    class OutcomeUnknown extends RuntimeException {
        private final UUID jobId;
        public OutcomeUnknown(String message, Throwable cause) { this(message, cause, null); }
        public OutcomeUnknown(String message, Throwable cause, UUID jobId) {
            super(message, cause); this.jobId = jobId;
        }
        public UUID jobId() { return jobId; }
    }

    default com.rigour.merchant.api.v1.model.CustomerSyncJob customerJob(CallerIdentity caller, UUID jobId) {
        throw new UnsupportedOperationException("CRM客户端尚未支持核对原任务");
    }

    default java.util.List<com.rigour.merchant.api.v1.model.CustomerSyncJob> customerJobs(
            CallerIdentity caller, UUID connector, Instant from, Instant to) {
        return java.util.List.of();
    }
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
