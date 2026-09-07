package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 订货宝同步人工裁决视图；供同步中心展示和后续重放审计。 */
public record DhbManualResolutionView(
        /** 裁决ID。 */ UUID id,
        /** 租户ID。 */ UUID tenantId,
        /** 连接器ID。 */ UUID connectorId,
        /** 外部系统编码。 */ String sourceSystem,
        /** 裁决类型。 */ String resolutionType,
        /** 待处理来源对象类型。 */ String sourceObjectType,
        /** 待处理来源对象ID或单号。 */ String sourceId,
        /** 被选择的来源对象类型。 */ String selectedSourceObjectType,
        /** 被选择的来源对象ID或单号。 */ String selectedSourceId,
        /** 可选：被选择对象的内部对象类型。 */ String selectedInternalObjectType,
        /** 可选：被选择对象的内部对象ID。 */ Long selectedInternalObjectId,
        /** 证据JSON文本。 */ String evidenceJson,
        /** 裁决原因。 */ String reason,
        /** 状态：ACTIVE、SUPERSEDED、CANCELLED。 */ String status,
        /** 创建时间。 */ Instant createdAt,
        /** 更新时间。 */ Instant updatedAt) {
}
