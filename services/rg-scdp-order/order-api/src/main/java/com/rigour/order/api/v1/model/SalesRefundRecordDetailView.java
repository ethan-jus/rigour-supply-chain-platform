package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售退款记录详情视图。 */
public record SalesRefundRecordDetailView(
        Long id,
        String refundNo,
        Long orderId,
        String salesOrderNoSnapshot,
        Long customerId,
        String customerCodeSnapshot,
        String customerNameSnapshot,
        String refundStaffCode,
        String refundStaffNameSnapshot,
        Instant refundTime,
        String refundMethodCode,
        String refundStatusCode,
        BigDecimal refundAmount,
        List<String> voucherKeys,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public SalesRefundRecordDetailView {
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
    }
}
