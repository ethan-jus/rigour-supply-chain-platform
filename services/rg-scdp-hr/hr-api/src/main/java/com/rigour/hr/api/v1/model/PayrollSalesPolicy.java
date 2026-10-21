package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record PayrollSalesPolicy(
        BigDecimal processPercent,
        int dailyCheckins,
        BigDecimal checkinPenalty,
        int dailyVisits,
        int minimumSeconds,
        BigDecimal visitPenalty,
        boolean firstMonthExempt,
        List<PayrollTargetStage> targets,
        BigDecimal resultCap,
        String checkinCountMode) {
    public PayrollSalesPolicy {
        if (checkinCountMode == null) checkinCountMode = "UNIQUE_CUSTOMER";
    }
}
