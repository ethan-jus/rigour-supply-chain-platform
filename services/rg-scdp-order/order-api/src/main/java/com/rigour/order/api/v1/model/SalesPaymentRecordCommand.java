package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 销售回款记录保存参数。 */
public record SalesPaymentRecordCommand(
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
        Long orderId,
        String collectorStaffCode,
        String collectorNameSnapshot,
        Instant paymentTime,
        String paymentMethodCode,
        BigDecimal paidAmount,
        List<String> voucherKeys,
        String remark,
        Integer revision,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        String sourceModifierId,
        String sourceModifierName,
        String syncedBy,
        Instant syncedAt,
        String sourcePaymentStatusCode,
        String sourceCheckedBy,
        Instant sourceCheckedAt) {
    public SalesPaymentRecordCommand(
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
        Long orderId,
        String collectorStaffCode,
        String collectorNameSnapshot,
        Instant paymentTime,
        String paymentMethodCode,
        BigDecimal paidAmount,
        List<String> voucherKeys,
        String remark,
        Integer revision,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        String sourceModifierId,
        String sourceModifierName,
        String syncedBy,
        Instant syncedAt) {
        this(connectorId, sourceSystemCode, sourceDocumentNo, orderId, collectorStaffCode, collectorNameSnapshot, paymentTime, paymentMethodCode, paidAmount, voucherKeys, remark, revision, sourceCreatedAt, sourceUpdatedAt, sourceModifierId, sourceModifierName, syncedBy, syncedAt, null, null, null);
    }

    public SalesPaymentRecordCommand {
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
    }

    /** 兼容旧调用：不传来源审计字段时，行为与既有同步一致。 */
    public SalesPaymentRecordCommand(
            UUID connectorId,
            String sourceSystemCode,
            String sourceDocumentNo,
            Long orderId,
            String collectorStaffCode,
            String collectorNameSnapshot,
            Instant paymentTime,
            String paymentMethodCode,
            BigDecimal paidAmount,
            List<String> voucherKeys,
            String remark,
            Integer revision) {
        this(connectorId, sourceSystemCode, sourceDocumentNo, orderId, collectorStaffCode,
                collectorNameSnapshot, paymentTime, paymentMethodCode, paidAmount, voucherKeys,
                remark, revision, null, null, null, null, null, null);
    }

    public SalesPaymentRecordCommand(
            Long orderId,
            String collectorStaffCode,
            String collectorNameSnapshot,
            Instant paymentTime,
            String paymentMethodCode,
            BigDecimal paidAmount,
            List<String> voucherKeys,
            String remark,
            Integer revision) {
        this(null, null, null, orderId, collectorStaffCode, collectorNameSnapshot, paymentTime,
                paymentMethodCode, paidAmount, voucherKeys, remark, revision, null, null, null,
                null, null, null);
    }
}
