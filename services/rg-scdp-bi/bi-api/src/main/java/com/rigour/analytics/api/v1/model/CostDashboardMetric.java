package com.rigour.analytics.api.v1.model;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardMetric(
        String amount,
        long count,
        String unpaid,
        long draftCount,
        String draftAmount,
        long missingCount,
        String unassigned, String linkedAmount, long reviewCount, String reviewAmount) {}
