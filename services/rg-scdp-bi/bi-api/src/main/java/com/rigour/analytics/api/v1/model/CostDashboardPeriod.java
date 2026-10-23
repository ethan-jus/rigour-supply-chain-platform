package com.rigour.analytics.api.v1.model;

import java.time.LocalDate;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardPeriod(
        LocalDate from,
        LocalDate to,
        LocalDate previousFrom,
        LocalDate previousTo,
        String compare) {}
