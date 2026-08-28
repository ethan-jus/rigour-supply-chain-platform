package com.rigour.order.api.v1.model;

/** 外部来源销售订单投影资料更新命令；只允许同步服务维护来源展示和对账字段。 */
public record SalesOrderSourceProjectionCommand(
        String sourceStatusCode,
        String sourceCreatorId,
        String sourceCreatorStaffCode,
        String sourceCreatorName,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerStaffCode,
        String ownerStaffNameSnapshot,
        Integer revision) {
}
