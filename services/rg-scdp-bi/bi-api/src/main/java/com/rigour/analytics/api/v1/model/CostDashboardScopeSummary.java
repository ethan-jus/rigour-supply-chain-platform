package com.rigour.analytics.api.v1.model;

import java.util.List;

/** 同一快照、期间和归属下的独立成本口径，不将采购、损耗与经营费用相加。 */
public record CostDashboardScopeSummary(
        String scope,
        String label,
        CostDashboardMetric current,
        CostDashboardMetric previous,
        boolean comparisonAvailable,
        String delta,
        String growth,
        List<CostDashboardGroup> components) {}
