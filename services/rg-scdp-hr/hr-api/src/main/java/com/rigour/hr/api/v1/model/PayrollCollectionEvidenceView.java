package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollCollectionEvidenceView(
        String sourceId,
        String orderNo,
        LocalDate date,
        BigDecimal amount,
        String kind,
        String settlementPaymentId,
        String settlementPaymentNo,
        String paymentOwnerCode,
        String paymentOwnerName,
        String paymentOwnerEvidence,
        LocalDate actualCashDate,
        BigDecimal refundedAmount,
        List<ProductLine> productLines) {
    public PayrollCollectionEvidenceView(
            String sourceId, String orderNo, LocalDate date, BigDecimal amount, String kind) {
        this(sourceId, orderNo, date, amount, kind, null, null, null, null, null, null, null, null);
    }

    public record ProductLine(
            String lineId,
            Long productId,
            Long productVariantId,
            String productName,
            String unit,
            BigDecimal quantity,
            BigDecimal lineAmount) {}
}
