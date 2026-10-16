package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record ErpCostPageView(
        long total,
        int begin,
        int step,
        BigDecimal confirmedAmount,
        BigDecimal paidAmount,
        long pendingCount,
        List<ErpCostDetailView> items,
        BigDecimal registeredAmount,
        Long missingAmountCount) {
    public ErpCostPageView(
            long total,
            int begin,
            int step,
            BigDecimal confirmedAmount,
            BigDecimal paidAmount,
            long pendingCount,
            List<ErpCostDetailView> items) {
        this(total, begin, step, confirmedAmount, paidAmount, pendingCount, items, null, null);
    }
}
