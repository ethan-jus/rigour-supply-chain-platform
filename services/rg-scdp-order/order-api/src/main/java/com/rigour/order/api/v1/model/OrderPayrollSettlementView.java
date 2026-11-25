package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 薪酬回款业绩使用的整单首次付清事实。 */
public record OrderPayrollSettlementView(
        String orderId,
        String orderNo,
        String originalEmployeeCode,
        String paymentId,
        String paymentNo,
        String paymentOwnerCode,
        String paymentOwnerName,
        String paymentOwnerEvidence,
        LocalDate fullyPaidDate,
        LocalDate actualCashDate,
        BigDecimal orderAmount,
        BigDecimal refundedAmount,
        String qualityStatus,
        List<ProductLine> lines) {

    public record ProductLine(
            String lineId,
            Long productId,
            Long productVariantId,
            String productName,
            String unit,
            BigDecimal quantity,
            BigDecimal lineAmount) {}
}
