package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 订货宝同步运行批次；用于同步中心查看每次调度、手动、修复或重放的结果。 */
public record DhbSyncRunAuditView(
        /** 同步批次ID。 */ UUID runId,
        /** 租户ID。 */ UUID tenantId,
        /** 同步任务ID。 */ UUID taskId,
        /** 订货宝连接器ID。 */ UUID connectorId,
        /** 同步任务编码。 */ String taskCode,
        /** 同步对象类型。 */ String objectType,
        /** 触发方式：SCHEDULED、MANUAL、RETRY、REPLAY。 */ String triggerType,
        /** 运行状态：QUEUED、RUNNING、SUCCEEDED、PARTIAL、FAILED、CANCELLED。 */ String status,
        /** 本轮窗口开始时间。 */ Instant windowFrom,
        /** 本轮窗口结束时间。 */ Instant windowTo,
        /** 拉取数量。 */ long fetchedCount,
        /** 接收数量。 */ long acceptedCount,
        /** 重复数量。 */ long duplicateCount,
        /** 拒绝数量。 */ long rejectedCount,
        /** 开始时间。 */ Instant startedAt,
        /** 结束时间。 */ Instant finishedAt,
        /** 错误码。 */ String errorCode,
        /** 错误消息，已过滤凭据和Raw明文。 */ String errorMessage) {
}
