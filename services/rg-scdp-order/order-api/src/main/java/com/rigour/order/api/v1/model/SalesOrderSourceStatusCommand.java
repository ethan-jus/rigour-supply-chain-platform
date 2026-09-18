package com.rigour.order.api.v1.model;

/** 外部来源销售订单状态更新命令；只维护来源展示和对账字段，不驱动我方人工流程。 */
public record SalesOrderSourceStatusCommand(String sourceStatusCode, Integer revision) {
}
