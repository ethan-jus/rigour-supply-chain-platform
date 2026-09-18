package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 订货宝同步日志明细；面向开发和运维，不承载第三方凭据或Raw Payload。 */
public record DhbSyncLogDetailView(
        /** 日志ID。 */ UUID id,
        /** 租户ID。 */ UUID tenantId,
        /** 同步任务ID。 */ UUID taskId,
        /** 同步批次ID。 */ UUID runId,
        /** 连接器ID。 */ UUID connectorId,
        /** 同步任务编码。 */ String taskCode,
        /** 同步对象类型。 */ String objectType,
        /** 日志级别。 */ String level,
        /** 日志消息。 */ String message,
        /** 错误码。 */ String errorCode,
        /** 发生时间。 */ Instant occurredAt) {
}
