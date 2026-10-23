package com.rigour.analytics.api.v1.model;

import java.util.List;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardReport(
        CostDashboardMeta meta,
        String scope,
        String category,
        String dimension,
        CostDashboardMetric current,
        CostDashboardMetric previous,
        String delta,
        String growth,
        String purchase,
        String loss,
        String increased,
        String decreased,
        List<CostDashboardChoice> cities,
        List<CostDashboardGroup> categories,
        List<CostDashboardGroup> cityGroups,
        List<CostDashboardCell> matrix,
        List<CostDashboardChange> changes,
        List<CostDashboardGroup> objects,
        String dimensionCoverage,
        List<CostDashboardChoice> dimensions,
        List<CostDashboardPoint> cumulative,
        List<CostDashboardMonth> months,
        List<CostDashboardRecordView> majorRecords,
        List<CostDashboardPayrollComponent> payrollComponents,
        String monthlyAmount,
        String previousMonthlyAmount) {}
