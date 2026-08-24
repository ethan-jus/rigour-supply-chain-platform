package com.rigour.merchant.application.port.out;

import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import java.util.List;
import java.util.UUID;

/** CRM 主数据同步持久化端口；实现必须在 SQL 条件中绑定 tenant_id。 */
public interface CrmMasterDataStore {
    default UUID startRun(UUID tenantId, UUID connectorId, UUID actorId,
                          CrmMasterDataObjectType objectType, int maxPages,
                          String triggerType) {
        return startRun(tenantId, connectorId, actorId, null, objectType, maxPages, triggerType);
    }

    UUID startRun(UUID tenantId, UUID connectorId, UUID actorId, UUID sourceTaskId,
                  CrmMasterDataObjectType objectType, int maxPages, String triggerType);

    /**
     * 记录尚未进入对象抓取阶段的可预期跳过，保证调度冲突和配置歧义也有持久化证据。
     */
    UUID recordSkippedRun(UUID tenantId, UUID connectorId, UUID sourceTaskId,
                          CrmMasterDataObjectType objectType, int maxPages,
                          String reasonCode, String reasonMessage);

    /** 同一调度目标涉及多个对象时必须原子记录，避免只留下部分跳过证据。 */
    default List<UUID> recordSkippedRuns(UUID tenantId, UUID connectorId, UUID sourceTaskId,
                                         List<CrmMasterDataObjectType> objectTypes, int maxPages,
                                         String reasonCode, String reasonMessage) {
        if (objectTypes == null || objectTypes.isEmpty()) return List.of();
        return objectTypes.stream()
                .map(type -> recordSkippedRun(tenantId, connectorId, sourceTaskId,
                        type, maxPages, reasonCode, reasonMessage))
                .toList();
    }

    ImportResult importRecord(UUID tenantId, UUID connectorId, UUID runId,
                              CrmMasterDataObjectType objectType, SourceRecord record);

    /**
     * 按批次导入同一对象类型，减少每条记录独立事务的提交开销。
     * 默认实现保留端口兼容性；数据库实现应在一个受控事务内覆盖它。
     */
    default List<ImportResult> importRecords(UUID tenantId, UUID connectorId, UUID runId,
                                              CrmMasterDataObjectType objectType,
                                              List<SourceRecord> records) {
        if (records == null || records.isEmpty()) return List.of();
        return records.stream()
                .map(record -> importRecord(tenantId, connectorId, runId, objectType, record))
                .toList();
    }

    RunStatistics completeRun(UUID tenantId, UUID connectorId, UUID runId,
                              CrmMasterDataObjectType objectType,
                              RunStatistics statistics, boolean reconcileSourcePresence);

    default List<ExternalObjectMappingCommand> externalObjectMappings(
            UUID tenantId, UUID connectorId, UUID runId, CrmMasterDataObjectType objectType) {
        return List.of();
    }

    void failRun(UUID tenantId, UUID connectorId, UUID runId,
                 RunStatistics statistics, RuntimeException error);

    record ImportResult(long created, long changed, long repaired,
                        long duplicates, long rejected) {
        public static ImportResult createdOne() { return new ImportResult(1, 0, 0, 0, 0); }
        public static ImportResult changedOne() { return new ImportResult(0, 1, 0, 0, 0); }
        public static ImportResult repairedOne() { return new ImportResult(0, 0, 1, 0, 0); }
        public static ImportResult duplicateOne() { return new ImportResult(0, 0, 0, 1, 0); }
        public static ImportResult rejectedOne() { return new ImportResult(0, 0, 0, 0, 1); }
    }

    record RunStatistics(long fetched, long created, long changed, long repaired,
                         long duplicates, long absent, long rejected, int pages) {
    }
}
