package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 资金收付款单详情视图。 */
public record FundDocumentDetailView(
        Long id,
        String documentNo,
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
        List<FundDocumentAttachmentView> attachments,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public FundDocumentDetailView {
        sourceAttachmentKeys = sourceAttachmentKeys == null ? List.of() : List.copyOf(sourceAttachmentKeys);
        voucherKeys = voucherKeys == null ? List.of() : List.copyOf(voucherKeys);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public FundDocumentDetailView(
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
            List<FundDocumentAttachmentView> attachments,
            String remark,
            Integer revision,
            String createdBy,
            Instant createdTime,
            String updatedBy,
            Instant updatedTime) {
        this(id, documentNo, null, null, directionCode, relatedOrderId, salesOrderNoSnapshot,
                customerId, customerCodeSnapshot, customerNameSnapshot, counterpartyTypeCode,
                counterpartyCodeSnapshot, counterpartyNameSnapshot, handlerStaffCode,
                handlerStaffNameSnapshot, occurredTime, settlementMethodCode, businessTypeCode,
                documentStatusCode, amount, sourceDocumentNo, sourceOrderNo, paymentSerialNo,
                bankAccountName, bankName, bankAccountNo, submittedAt, confirmedAt,
                sourceAttachmentKeys, voucherKeys, attachments, remark, revision, createdBy,
                createdTime, updatedBy, updatedTime);
    }

    public FundDocumentDetailView(
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
        this(id, documentNo, null, null, directionCode, relatedOrderId, salesOrderNoSnapshot,
                customerId, customerCodeSnapshot, customerNameSnapshot, counterpartyTypeCode,
                counterpartyCodeSnapshot, counterpartyNameSnapshot, handlerStaffCode,
                handlerStaffNameSnapshot, occurredTime, settlementMethodCode, businessTypeCode,
                documentStatusCode, amount, null, null, null, null, null, null,
                null, null, List.of(), voucherKeys, List.of(), remark, revision, createdBy,
                createdTime, updatedBy, updatedTime);
    }
}
