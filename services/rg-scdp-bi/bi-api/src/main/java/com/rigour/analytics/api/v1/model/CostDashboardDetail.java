package com.rigour.analytics.api.v1.model;

import java.util.List;
import java.util.Map;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardDetail(
        CostDashboardMeta meta,
        CostDashboardRecordView record,
        Map<String, String> dimensions,
        String remark,
        List<String> originalNumbers,
        String sourceTable,
        long revision,
        String mappingState,
        String sourceRoute) {}
