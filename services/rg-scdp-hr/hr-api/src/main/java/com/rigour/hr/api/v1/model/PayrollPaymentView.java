package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PayrollPaymentView(
        String id,
        String requestId,
        LocalDate paidDate,
        BigDecimal amount,
        String referenceNo,
        String reason,
        Instant createdTime,
        List<PayrollPaymentAttachmentView> attachments) {
    public PayrollPaymentView {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public PayrollPaymentView(String id, String requestId, LocalDate paidDate, BigDecimal amount,
            String referenceNo, String reason, Instant createdTime) {
        this(id, requestId, paidDate, amount, referenceNo, reason, createdTime, List.of());
    }
}
