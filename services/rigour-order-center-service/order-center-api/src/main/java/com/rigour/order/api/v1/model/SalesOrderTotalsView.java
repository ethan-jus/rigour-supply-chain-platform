package com.rigour.order.api.v1.model;

import java.math.BigDecimal;

/** 销售订单列表筛选结果汇总。 */
public record SalesOrderTotalsView(
        long total,
        BigDecimal totalQuantity,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount) {
}
