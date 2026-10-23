package com.rigour.analytics.api.v1.model;

import java.time.LocalDate;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardMonth(
        int year, int month, String label, String current, String previous, LocalDate cutoff) {}
