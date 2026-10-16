package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.Map;

/** 当前筛选的全月汇总；金额仅包含完整结果，阶段人数不受阶段筛选影响。 */
public record PayrollSummaryView(
        long total,
        long completeCount,
        long pendingCount,
        BigDecimal grossPay,
        BigDecimal netPay,
        Map<String, Long> statusCounts) {}
