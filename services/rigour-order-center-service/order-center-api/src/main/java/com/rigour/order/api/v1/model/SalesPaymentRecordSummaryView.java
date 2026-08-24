package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 销售回款记录列表视图。 */
public record SalesPaymentRecordSummaryView(
        Long id,
        String paymentNo,
        Long orderId,
        String salesOrderNoSnapshot,
        Long customerId,
        String customerCodeSnapshot,
        String customerNameSnapshot,
        String collectorStaffCode,
        String collectorNameSnapshot,
        Instant paymentTime,
        String paymentMethodCode,
        BigDecimal paidAmount,
        Integer revision,
        Instant updatedTime) {
}
