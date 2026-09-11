package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 资金收付款单保存参数。 */
public record FundDocumentCommand(
        UUID connectorId,
        String sourceSystemCode,
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
        String sourceDocumentNo,
        String sourceOrderNo,
        String paymentSerialNo,
        String bankAccountName,
        String bankName,
        String bankAccountNo,
        Instant submittedAt,
        Instant confirmedAt,
        List<String> sourceAttachmentKeys,
        List<String> voucherKeys,
        String remark,
        Integer revision) {
    public FundDocumentCommand {
        sourceAttachmentKeys = sourceAttachmentKeys == null ? List.of() : List.copyOf(sourceAttachmentKeys);
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
    }

    public FundDocumentCommand(
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
            String sourceDocumentNo,
            String sourceOrderNo,
            String paymentSerialNo,
            String bankAccountName,
            String bankName,
            String bankAccountNo,
            Instant submittedAt,
            Instant confirmedAt,
            List<String> sourceAttachmentKeys,
            List<String> voucherKeys,
            String remark,
            Integer revision) {
        this(null, null, directionCode, relatedOrderId, salesOrderNoSnapshot, customerId,
                customerCodeSnapshot, customerNameSnapshot, counterpartyTypeCode,
                counterpartyCodeSnapshot, counterpartyNameSnapshot, handlerStaffCode,
                handlerStaffNameSnapshot, occurredTime, settlementMethodCode,
                businessTypeCode, documentStatusCode, amount, sourceDocumentNo, sourceOrderNo,
                paymentSerialNo, bankAccountName, bankName, bankAccountNo, submittedAt,
                confirmedAt, sourceAttachmentKeys, voucherKeys, remark, revision);
    }

    public FundDocumentCommand(
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
        this(null, null, directionCode, relatedOrderId, salesOrderNoSnapshot, customerId,
                customerCodeSnapshot, customerNameSnapshot, counterpartyTypeCode,
                counterpartyCodeSnapshot, counterpartyNameSnapshot, handlerStaffCode,
                handlerStaffNameSnapshot, occurredTime, settlementMethodCode,
                businessTypeCode, documentStatusCode, amount, null, null, null,
                null, null, null, null, null, List.of(), voucherKeys, remark, revision);
    }
}
