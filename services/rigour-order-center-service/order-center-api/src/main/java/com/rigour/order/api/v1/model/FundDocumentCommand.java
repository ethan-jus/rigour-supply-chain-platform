package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 资金收付款单保存参数。 */
public record FundDocumentCommand(
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
        Integer revision) {
    public FundDocumentCommand {
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
    }
}
