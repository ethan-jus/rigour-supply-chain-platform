package com.rigour.analytics.api.v1.model;

import java.util.List;

/** 不同角色进入供应链 BI 时的默认关注点。 */
public record SupplyDashboardRolePerspectiveView(
        String roleCode,
        String roleName,
        List<String> primaryMetricCodes,
        List<String> primarySectionCodes,
        String description) {
    public SupplyDashboardRolePerspectiveView {
        primaryMetricCodes = List.copyOf(primaryMetricCodes == null ? List.of() : primaryMetricCodes);
        primarySectionCodes = List.copyOf(primarySectionCodes == null ? List.of() : primarySectionCodes);
    }
}
