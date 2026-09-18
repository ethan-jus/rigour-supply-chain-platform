package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售退款记录保存参数。 */
public record SalesRefundRecordCommand(
        Long orderId,
        String refundStaffCode,
        String refundStaffNameSnapshot,
        Instant refundTime,
        String refundMethodCode,
        String refundStatusCode,
        BigDecimal refundAmount,
        List<String> voucherKeys,
        String remark,
        Integer revision) {
    public SalesRefundRecordCommand {
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
    }
}
