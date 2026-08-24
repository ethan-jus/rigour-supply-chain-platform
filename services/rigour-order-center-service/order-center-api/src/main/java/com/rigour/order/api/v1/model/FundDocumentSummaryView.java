package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 资金收付款单列表视图。 */
public record FundDocumentSummaryView(
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
        Integer revision,
        Instant updatedTime) {
}
