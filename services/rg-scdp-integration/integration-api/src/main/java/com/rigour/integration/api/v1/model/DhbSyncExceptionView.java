package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 订货宝同步中心异常记录；对应死信队列，供开发和运维定位、重放或忽略。 */
public record DhbSyncExceptionView(
        /** 异常记录ID。 */ UUID id,
        /** 租户ID。 */ UUID tenantId,
        /** 同步批次ID。 */ UUID runId,
        /** Raw Landing记录ID。 */ UUID rawLandingId,
        /** 外部系统编码。 */ String sourceSystem,
        /** 外部对象类型。 */ String sourceObjectType,
        /** 外部对象ID。 */ String sourceId,
        /** 异常状态：OPEN、REPLAYING、RESOLVED、IGNORED。 */ String status,
        /** 已尝试次数。 */ int attempts,
        /** 下一次可重试时间。 */ Instant nextRetryAt,
        /** 最近一次错误码。 */ String lastErrorCode,
        /** 最近一次错误消息，已过滤凭据和Raw明文。 */ String lastErrorMessage,
        /** 处理完成时间。 */ Instant resolvedAt,
        /** 创建时间。 */ Instant createdAt,
        /** 更新时间。 */ Instant updatedAt) {
}
