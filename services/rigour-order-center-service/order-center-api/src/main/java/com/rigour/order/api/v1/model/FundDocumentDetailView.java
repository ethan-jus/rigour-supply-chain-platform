package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 资金收付款单详情视图。 */
public record FundDocumentDetailView(
        Long id,
        String documentNo,
        String directionCode,
        Long relatedOrderId,
        String salesOrderNoSnapshot,
        Long customerId,
        String customerCodeSnapshot,
        String customerNameSnapshot,
        String counterpartyTypeCode,
        String counterpartyCodeSnapshot,
        String counterpartyNameSnapshot,
        String handlerStaffCode,
        String handlerStaffNameSnapshot,
        Instant occurredTime,
        String settlementMethodCode,
        String businessTypeCode,
        String documentStatusCode,
        BigDecimal amount,
        List<String> voucherKeys,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public FundDocumentDetailView {
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
    }
}
