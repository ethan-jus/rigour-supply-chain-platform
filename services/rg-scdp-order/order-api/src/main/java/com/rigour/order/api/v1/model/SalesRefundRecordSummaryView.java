package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 销售退款记录列表视图。 */
public record SalesRefundRecordSummaryView(
        Long id,
        String refundNo,
        Long orderId,
        String salesOrderNoSnapshot,
        Long customerId,
        String customerCodeSnapshot,
        String customerNameSnapshot,
        String refundStaffCode,
        String refundStaffNameSnapshot,
        Instant refundTime,
        String refundMethodCode,
        String refundStatusCode,
        BigDecimal refundAmount,
        Integer revision,
        Instant updatedTime) {
}
