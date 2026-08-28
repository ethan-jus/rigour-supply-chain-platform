package com.rigour.erp.infrastructure.persistence.repository;

import com.rigour.erp.application.port.out.ErpSyncRunAuditStore;
import com.rigour.erp.infrastructure.persistence.entity.MasterDataSyncRunEntity;
import com.rigour.erp.infrastructure.persistence.mapper.MasterDataSyncRunMapper;
import com.rigour.shared.core.sync.ExternalSourceCodes;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 基于现有 ERP 同步批次表的调度跳过审计仓储。 */
@Repository
public class MybatisPlusErpSyncRunAuditStore implements ErpSyncRunAuditStore {
    private static final String SOURCE_SYSTEM = ExternalSourceCodes.DOMAIN_DINGHUOBAO;
    private final MasterDataSyncRunMapper syncRunMapper;
    private final Clock clock;

    public MybatisPlusErpSyncRunAuditStore(MasterDataSyncRunMapper syncRunMapper, Clock clock) {
        this.syncRunMapper = syncRunMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID recordScheduledSkip(UUID tenantId, UUID connectorId, UUID sourceTaskId,
                                    String blockedObjectType, int maxPages,
                                    ScheduledSkipReason reason) {
        Objects.requireNonNull(tenantId, "tenantId不能为空");
        Objects.requireNonNull(connectorId, "connectorId不能为空");
        Objects.requireNonNull(sourceTaskId, "sourceTaskId不能为空");
        Objects.requireNonNull(reason, "reason不能为空");
        String objectType = requireObjectType(blockedObjectType);
        if (maxPages < 1 || maxPages > 100) {
            throw new IllegalArgumentException("maxPages必须在1到100之间");
        }

        UUID runId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(clock);
        MasterDataSyncRunEntity entity = new MasterDataSyncRunEntity();
        entity.id = runId.toString();
        entity.tenantId = tenantId.toString();
        entity.connectorId = connectorId.toString();
        entity.sourceTaskId = sourceTaskId.toString();
        entity.sourceSystem = SOURCE_SYSTEM;
        entity.objectType = objectType;
        entity.triggerType = "SCHEDULED";
        entity.status = "SKIPPED";
        entity.maxPages = maxPages;
        entity.pageSize = 0;
        entity.fetchedCount = 0L;
        entity.createdCount = 0L;
        entity.changedCount = 0L;
        entity.duplicateCount = 0L;
        entity.rejectedCount = 0L;
        entity.unmappedCount = 0L;
        entity.errorCode = reason.code();
        entity.errorMessage = reason.message();
        entity.startedAt = now;
        entity.finishedAt = now;
        entity.createdTime = now;
        entity.updatedTime = now;
        syncRunMapper.insert(entity);
        return runId;
    }

    private static String requireObjectType(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("blockedObjectType必须是1到32位大写业务编码");
        }
        return value;
    }
}
