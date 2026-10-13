package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollCollectionEvidenceView(
        String sourceId, String orderNo, LocalDate date, BigDecimal amount, String kind) {}
