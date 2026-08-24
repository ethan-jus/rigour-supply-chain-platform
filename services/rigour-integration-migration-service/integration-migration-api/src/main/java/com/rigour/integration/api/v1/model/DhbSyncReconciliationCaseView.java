package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 订货宝同步对账差异；记录来源、内部落库和核对结果之间的不一致。 */
public record DhbSyncReconciliationCaseView(
        /** 对账记录ID。 */ UUID id,
        /** 租户ID。 */ UUID tenantId,
        /** 同步批次ID。 */ UUID runId,
        /** 外部系统编码。 */ String sourceSystem,
        /** 外部对象类型。 */ String sourceObjectType,
        /** 业务核对键。 */ String businessKey,
        /** 核对类型，例如COUNT、DETAIL、HASH。 */ String checkType,
        /** 期望值JSON文本。 */ String expectedValueJson,
        /** 实际值JSON文本。 */ String actualValueJson,
        /** 状态：OPEN、ACKNOWLEDGED、RESOLVED、IGNORED。 */ String status,
        /** 严重级别：INFO、WARN、ERROR。 */ String severity,
        /** 差异说明。 */ String message,
        /** 处理完成时间。 */ Instant resolvedAt,
        /** 创建时间。 */ Instant createdAt,
        /** 更新时间。 */ Instant updatedAt) {
}
