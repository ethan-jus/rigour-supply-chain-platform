package com.rigour.analytics.api.v1.model;

import java.time.Instant;
import java.util.List;

/** 供应链 BI 来源、业务主表和 BI 表的只读对账结果。 */
public record SupplyDashboardReconciliationView(
        Instant from,
        Instant to,
        Instant generatedAt,
        String status,
        List<SupplyDashboardReconciliationItemView> items) {
    public SupplyDashboardReconciliationView {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
