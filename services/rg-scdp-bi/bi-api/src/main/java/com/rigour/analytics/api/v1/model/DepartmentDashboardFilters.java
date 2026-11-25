package com.rigour.analytics.api.v1.model;

import java.util.List;

public record DepartmentDashboardFilters(List<Node> departments, List<Node> customerRegions, String defaultDepartmentCode) {
    public record Node(String code, String name, String parentCode) {}
}
