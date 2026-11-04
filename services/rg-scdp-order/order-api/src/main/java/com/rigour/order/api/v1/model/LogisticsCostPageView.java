package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

public record LogisticsCostPageView(
        long total,
        int begin,
        int step,
        BigDecimal confirmedAmount,
        BigDecimal paidAmount,
        long pendingCount,
        List<LogisticsCostDetailView> items,
        BigDecimal registeredAmount,
        Long missingAmountCount,
        BigDecimal pendingAmount,
        Long thirdPartyCount) {
    public LogisticsCostPageView withItems(List<LogisticsCostDetailView> value) {
        return new LogisticsCostPageView(total, begin, step, confirmedAmount, paidAmount, pendingCount, value, registeredAmount, missingAmountCount, pendingAmount, thirdPartyCount);
    }

    public LogisticsCostPageView(
        long total,
        int begin,
        int step,
        BigDecimal confirmedAmount,
        BigDecimal paidAmount,
        long pendingCount,
        List<LogisticsCostDetailView> items,
        BigDecimal registeredAmount,
        Long missingAmountCount) {
        this(total, begin, step, confirmedAmount, paidAmount, pendingCount, items, registeredAmount, missingAmountCount, null, null);
    }

    public LogisticsCostPageView(
            long total,
            int begin,
            int step,
            BigDecimal confirmedAmount,
            BigDecimal paidAmount,
            long pendingCount,
            List<LogisticsCostDetailView> items) {
        this(total, begin, step, confirmedAmount, paidAmount, pendingCount, items, null, null);
    }
}
