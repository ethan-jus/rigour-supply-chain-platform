package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PaymentVoucherAuditModels {
    private PaymentVoucherAuditModels() {}
    public record Evidence(String key, BigDecimal amount, String transactionNo, String note,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Instant paidTime,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String paidTimePrecision,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String recognitionStatus,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String payee,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String paymentResult) {
        public Evidence(String key, BigDecimal amount, String transactionNo, String note) {
            this(key, amount, transactionNo, note, null, null, null, null, null);
        }
    }
    public record Payment(String id, String paymentNo, String orderId, String orderNo,
            String customer, String salesperson, BigDecimal amount, Instant time, String status,
            boolean excluded, String primaryTransaction, List<String> attachmentKeys, List<Evidence> evidence) {}
    public record Review(String id, String fingerprint, String conclusion, String note, String actor,
            Instant time, List<String> paymentIds, List<String> reasonCodes) {
        public Review(String id, String fingerprint, String conclusion, String note, String actor,
                Instant time, List<String> paymentIds) {
            this(id, fingerprint, conclusion, note, actor, time, paymentIds, List.of());
        }
    }
    public record AuditGroup(String key, String kind, String transactionNo, String imageKey,
            String result, List<String> reasons, BigDecimal voucherAmount, BigDecimal allocatedAmount,
            BigDecimal excessAmount, int unresolvedPayments, List<Payment> payments,
            String fingerprint, List<Review> reviews, boolean reviewStale) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public String auditStatusCode() {
            if (reviewStale) return "PENDING_REVIEW";
            if (!reviews.isEmpty()) return switch (reviews.getFirst().conclusion()) {
                case "NORMAL", "NORMAL_COMBINED" -> "NORMAL";
                case "ABNORMAL", "CONFIRMED_DUPLICATE" -> "ABNORMAL";
                default -> "PENDING_REVIEW";
            };
            // 新自动识别只提供文字和金额证据，不能自动替代人工对收款方及真实性的核查。
            boolean automaticEvidence=payments.stream().flatMap(p -> p.evidence().stream()).anyMatch(e -> e.recognitionStatus()!=null);
            return "BALANCED".equals(result) && !automaticEvidence ? "NORMAL" : "PENDING_REVIEW";
        }
        @com.fasterxml.jackson.annotation.JsonProperty
        public List<String> reasonCodes() {
            if ("NORMAL".equals(auditStatusCode())) return List.of();
            if (!reviewStale && !reviews.isEmpty() && !reviews.getFirst().reasonCodes().isEmpty())
                return reviews.getFirst().reasonCodes();
            return List.of(switch (result) {
                case "EXCESS" -> "DUPLICATE_ALLOCATION";
                case "AMOUNT_MISMATCH", "UNALLOCATED" -> "AMOUNT_MISMATCH";
                case "CONFLICT", "IMAGE_REUSE" -> "TRANSACTION_CONFLICT";
                case "MISSING_EVIDENCE" -> payments.stream().allMatch(p -> p.attachmentKeys().isEmpty())
                        ? "MISSING_VOUCHER" : "INCOMPLETE_EVIDENCE";
                case "PENDING_ALLOCATION", "BALANCED" -> "INCOMPLETE_EVIDENCE";
                default -> "OTHER";
            });
        }
        @com.fasterxml.jackson.annotation.JsonProperty
        public String reviewMethod() {
            return !reviewStale && !reviews.isEmpty() ? "MANUAL" : "SYSTEM";
        }
    }
    public record VoucherTime(Instant paidTime, String precision) {}
    public record PaymentAuditState(String statusCode, List<String> reasonCodes, List<VoucherTime> voucherTimes) {
        public PaymentAuditState(String statusCode,List<String> reasonCodes) {this(statusCode,reasonCodes,List.of());}
    }
    public record Scan(Instant scannedAt, int paymentsScanned, int paymentsWithEvidence,
            Map<String, Long> counts, List<AuditGroup> groups) {}
    public record ReviewRequest(String groupKey, String fingerprint, String conclusion, String note, List<String> reasonCodes) {
        public ReviewRequest(String groupKey, String fingerprint, String conclusion, String note) {
            this(groupKey, fingerprint, conclusion, note, List.of());
        }
        public ReviewRequest { reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes); }
    }
}
