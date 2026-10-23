package com.rigour.analytics.api.v1.model;

import java.util.List;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardRecords(
        CostDashboardMeta meta,
        CostDashboardRecordSummary summary,
        int page,
        int size,
        List<CostDashboardRecordView> items) {}
