package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentVoucherModels {
    private PaymentVoucherModels() {}

    /** 凭证金额是图片所载金额，不参与回款汇总，也不代表整张订单的实收金额。 */
    public record VoucherTransaction(String voucherKey, BigDecimal voucherAmount,
            String transactionNo, String evidenceNote, String url) {}

    public record TransactionMatch(String paymentId, String paymentNo, String orderNo,
            String customerName, String salesperson, BigDecimal paidAmount,
            Instant paymentTime, String paymentStatusCode, boolean deleted,
            String voucherKey, BigDecimal voucherAmount, String evidenceNote) {}
}
