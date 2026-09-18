package com.rigour.analytics.api.v1.model;

import java.time.Instant;
import java.util.List;

/** 供应链 BI 数据可信度总览。 */
public record SupplyDashboardDataTrustView(
        Instant generatedAt,
        String overallStatus,
        String overallDescription,
        SupplyDashboardRefreshRunView latestRefreshRun,
        List<SupplyDashboardDataTrustSourceView> sources) {
    public SupplyDashboardDataTrustView {
        sources = List.copyOf(sources == null ? List.of() : sources);
    }
}
