package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;

public record SalesPerformanceRow(
        String employee,
        String status,
        BigDecimal settled,
        BigDecimal target,
        Integer checkins,
        Integer visits,
        Integer missingVisits) {}
