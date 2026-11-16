package com.rigour.analytics.application.model;

import java.math.BigDecimal;

public record SalesDashboardSummary(
        BigDecimal sales, BigDecimal receipts, long customers, long newCustomers,
        long returningCustomers, long annualReturningCustomers) {}
