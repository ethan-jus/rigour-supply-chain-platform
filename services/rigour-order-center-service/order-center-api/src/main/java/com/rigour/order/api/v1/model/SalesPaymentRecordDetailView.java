package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 销售回款记录详情视图。 */
public record SalesPaymentRecordDetailView(
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
        List<String> voucherKeys,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public SalesPaymentRecordDetailView {
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
    }

    public SalesPaymentRecordDetailView(
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
            List<String> voucherKeys,
            String remark,
            Integer revision,
            String createdBy,
            Instant createdTime,
            String updatedBy,
            Instant updatedTime) {
        this(id, paymentNo, null, null, null, orderId, salesOrderNoSnapshot, customerId,
                customerCodeSnapshot, customerNameSnapshot, collectorStaffCode,
                collectorNameSnapshot, paymentTime, paymentMethodCode, paidAmount, voucherKeys,
                remark, revision, createdBy, createdTime, updatedBy, updatedTime);
    }
}
