package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售回款记录保存参数。 */
public record SalesPaymentRecordCommand(
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
}
