package com.rigour.order.api.v1.model;

import java.time.Instant;

/** 外部来源销售订单投影资料更新命令；只允许同步服务维护来源展示和对账字段。 */
public record SalesOrderSourceProjectionCommand(
        String sourceStatusCode,
        String sourceCreatorId,
        String sourceCreatorStaffCode,
        String sourceCreatorName,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerEmployeeCode,
        String ownerEmployeeNameSnapshot,
        String regionCode,
        Integer revision,
        // 来源审计与同步审计：同步服务写入，列表「创建人/修改人/同步人」列直接取这些值。
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        String sourceModifierId,
        String sourceModifierName,
        String syncedBy,
        Instant syncedAt) {

    /** 兼容旧调用：不传审计字段时，行为与既有来源投影一致。 */
    public SalesOrderSourceProjectionCommand(
            String sourceStatusCode,
            String sourceCreatorId,
            String sourceCreatorStaffCode,
            String sourceCreatorName,
            String ownerSalesUserId,
            String ownerSalesName,
            String ownerEmployeeCode,
            String ownerEmployeeNameSnapshot,
            String regionCode,
            Integer revision) {
        this(sourceStatusCode, sourceCreatorId, sourceCreatorStaffCode, sourceCreatorName,
                ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot,
                regionCode, revision, null, null, null, null, null, null);
    }
}
