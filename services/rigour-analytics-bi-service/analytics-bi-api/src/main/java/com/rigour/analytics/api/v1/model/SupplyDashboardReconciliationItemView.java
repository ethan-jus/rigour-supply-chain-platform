package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/** 供应链 BI 单项对账摘要。 */
public record SupplyDashboardReconciliationItemView(
        String subjectCode,
        String subjectName,
        Long sourceRowCount,
        Long businessRowCount,
        Long biRowCount,
        BigDecimal sourceAmount,
        BigDecimal businessAmount,
        BigDecimal biAmount,
        Long sourceBusinessRowDiff,
        Long businessBiRowDiff,
        BigDecimal businessBiAmountDiff,
        String status,
        String description) {
}
