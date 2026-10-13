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
        List<ErpCostDetailView> items) {}
