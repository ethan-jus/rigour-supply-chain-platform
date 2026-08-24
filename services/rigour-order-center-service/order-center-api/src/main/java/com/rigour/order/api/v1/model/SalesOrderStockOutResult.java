package com.rigour.order.api.v1.model;

import java.time.Instant;

/** 销售订单一键出库结果；包含 ERP 出库单和 Order 最新订单状态。 */
public record SalesOrderStockOutResult(
        Long stockOutOrderId,
        String stockOutNo,
        Instant stockOutTime,
        SalesOrderDetailView salesOrder) {
}
