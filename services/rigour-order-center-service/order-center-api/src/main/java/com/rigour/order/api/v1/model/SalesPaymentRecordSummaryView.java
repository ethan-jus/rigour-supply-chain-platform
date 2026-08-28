package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 销售回款记录列表视图。 */
public record SalesPaymentRecordSummaryView(
        Long id,
        String paymentNo,
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
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
    public SalesPaymentRecordSummaryView(
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
        this(id, paymentNo, null, null, null, orderId, salesOrderNoSnapshot, customerId,
                customerCodeSnapshot, customerNameSnapshot, collectorStaffCode,
                collectorNameSnapshot, paymentTime, paymentMethodCode, paidAmount, revision,
                updatedTime);
    }
}
