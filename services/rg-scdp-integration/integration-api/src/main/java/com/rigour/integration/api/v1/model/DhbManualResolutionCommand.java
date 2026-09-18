package com.rigour.integration.api.v1.model;

import java.util.Map;
import java.util.UUID;

/** 订货宝同步人工裁决命令；只用于明确、可审计地处理来源关系歧义。 */
public record DhbManualResolutionCommand(
        /** 连接器ID。 */ UUID connectorId,
        /** 裁决类型，例如TRANSFER_INBOUND_RECEIPT。 */ String resolutionType,
        /** 待处理来源对象类型，例如ERP_STOCK_OUT。 */ String sourceObjectType,
        /** 待处理来源对象ID或单号。 */ String sourceId,
        /** 被选择的来源对象类型，例如WAREHOUSING_RECEIPT。 */ String selectedSourceObjectType,
        /** 被选择的来源对象ID或单号。 */ String selectedSourceId,
        /** 可选：被选择对象的内部对象类型。 */ String selectedInternalObjectType,
        /** 可选：被选择对象的内部对象ID。 */ Long selectedInternalObjectId,
        /** 人工核对证据，保存候选项、原始单据线索等非敏感信息。 */ Map<String, Object> evidence,
        /** 人工裁决原因。 */ String reason) {
}
