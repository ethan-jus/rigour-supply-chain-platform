package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProcurementFreightConfirmation(
        String requestId,
        int orderRevision,
        Integer freightRevision,
        String freightRecordId,
        LocalDate costDate,
        BigDecimal actualAmount,
        String paymentStatusCode,
        String reason) {}
