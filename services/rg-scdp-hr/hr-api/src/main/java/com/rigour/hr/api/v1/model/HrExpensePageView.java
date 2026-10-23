package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** 汇总和分页使用完全相同的筛选与授权范围。 */
public record HrExpensePageView(
        long total,
        int begin,
        int step,
        List<HrExpenseDetailView> items,
        BigDecimal registeredAmount,
        BigDecimal confirmedAmount,
        BigDecimal paidAmount,
        long pendingCount,
        long missingAmountCount) {}
