package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PayrollPaymentView(
        String id,
        String requestId,
        LocalDate paidDate,
        BigDecimal amount,
        String referenceNo,
        String reason,
        Instant createdTime) {}
