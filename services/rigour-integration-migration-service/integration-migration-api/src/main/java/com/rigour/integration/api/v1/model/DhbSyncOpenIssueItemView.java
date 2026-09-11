package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 订货宝同步中心待处理来源对象；用于人工裁决、补映射和单对象重放。 */
public record DhbSyncOpenIssueItemView(
        /** 来源对象类型，例如 ERP_STOCK_OUT、SALES_ORDER。 */ String sourceObjectType,
        /** 来源对象ID或业务单号。 */ String sourceId,
        /** 订货宝连接器ID；来自最近死信批次或对账批次。 */ UUID connectorId,
        /** 最近同步批次ID。 */ UUID runId,
        /** 错误码。 */ String errorCode,
        /** 错误消息。 */ String errorMessage,
        /** 对账类型。 */ String checkType,
        /** 候选来源对象类型，例如 WAREHOUSING_RECEIPT。 */ String candidateSourceObjectType,
        /** 候选来源对象ID或单号。 */ List<String> candidateSourceIds,
        /** 是否必须人工裁决。 */ boolean manualResolutionRequired,
        /** 是否可直接单对象重放。 */ boolean replaySupported,
        /** 前端展示用处理建议。 */ String handlingAdvice,
        /** 最近更新时间。 */ Instant updatedAt) {
    public DhbSyncOpenIssueItemView {
        candidateSourceIds = candidateSourceIds == null ? List.of() : List.copyOf(candidateSourceIds);
    }
}
