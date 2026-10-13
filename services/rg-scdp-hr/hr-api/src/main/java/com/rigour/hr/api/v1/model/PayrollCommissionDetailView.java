package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record PayrollCommissionDetailView(
        String sourceLineId,
        String recipientRoleCode,
        String orderNo,
        String productName,
        String unitCode,
        BigDecimal quantity,
        String ruleId,
        String ruleName,
        int ruleRevision,
        BigDecimal accrued,
        BigDecimal released,
        String message) {}
