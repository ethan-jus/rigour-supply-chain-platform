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
        List<LogisticsCostDetailView> items) {}
