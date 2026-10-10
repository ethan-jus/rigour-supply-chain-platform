package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PaymentVoucherAuditModels {
    private PaymentVoucherAuditModels() {}
    public record Evidence(String key, BigDecimal amount, String transactionNo, String note) {}
    public record Payment(String id, String paymentNo, String orderId, String orderNo,
            String customer, String salesperson, BigDecimal amount, Instant time, String status,
            boolean excluded, String primaryTransaction, List<String> attachmentKeys, List<Evidence> evidence) {}
    public record Review(String id, String fingerprint, String conclusion, String note, String actor,
            Instant time, List<String> paymentIds) {}
    public record AuditGroup(String key, String kind, String transactionNo, String imageKey,
            String result, List<String> reasons, BigDecimal voucherAmount, BigDecimal allocatedAmount,
            BigDecimal excessAmount, int unresolvedPayments, List<Payment> payments,
            String fingerprint, List<Review> reviews, boolean reviewStale) {}
    public record Scan(Instant scannedAt, int paymentsScanned, int paymentsWithEvidence,
            Map<String, Long> counts, List<AuditGroup> groups) {}
    public record ReviewRequest(String groupKey, String fingerprint, String conclusion, String note) {}
}
