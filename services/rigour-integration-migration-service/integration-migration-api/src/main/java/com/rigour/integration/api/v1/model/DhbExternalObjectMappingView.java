package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 外部来源对象到我方业务对象的映射；只供同步中心和运维排查，不进入业务主流程页面。 */
public record DhbExternalObjectMappingView(
        /** 映射记录ID。 */ UUID id,
        /** 租户ID。 */ UUID tenantId,
        /** 订货宝连接器ID；历史记录可能为空。 */ UUID connectorId,
        /** 外部系统编码，订货宝为DHB。 */ String sourceSystem,
        /** 外部对象类型，例如PRODUCT、CUSTOMER、SALES_ORDER。 */ String sourceObjectType,
        /** 外部对象ID。 */ String sourceObjectId,
        /** 外部对象业务编号，便于人工核对。 */ String sourceObjectNo,
        /** 我方业务域，例如ERP、CRM、ORDER。 */ String internalDomain,
        /** 我方对象类型，例如PRODUCT、CUSTOMER、SALES_ORDER。 */ String internalObjectType,
        /** 我方业务对象ID；未映射、忽略或冲突时可以为空。 */ Long internalObjectId,
        /** 我方业务编号，便于人工核对。 */ String internalObjectNo,
        /** 映射状态：ACTIVE、REMOVED、CONFLICT、IGNORED。 */ String mappingStatus,
        /** 最近一次看到该外部对象的同步批次ID。 */ UUID lastSeenRunId,
        /** 最近一次看到该外部对象的时间。 */ Instant lastSeenAt,
        /** 外部对象被检测为删除或不可见的时间；不等同于我方业务删除。 */ Instant sourceDeletedAt,
        /** 最近一次外部原始数据摘要。 */ String payloadChecksum,
        /** 冲突原因。 */ String conflictReason,
        /** 运维备注。 */ String remark,
        /** 乐观锁版本。 */ long version,
        /** 创建时间。 */ Instant createdAt,
        /** 更新时间。 */ Instant updatedAt) {
}
