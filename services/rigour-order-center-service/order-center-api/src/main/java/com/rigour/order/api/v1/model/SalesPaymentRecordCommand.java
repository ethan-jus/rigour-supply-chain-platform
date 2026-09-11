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
        Integer revision) {
    public SalesPaymentRecordCommand {
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
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
                paymentMethodCode, paidAmount, voucherKeys, remark, revision);
    }
}
