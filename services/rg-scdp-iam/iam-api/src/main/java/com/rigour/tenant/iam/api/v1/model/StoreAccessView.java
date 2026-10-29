package com.rigour.tenant.iam.api.v1.model;

import java.util.List;
import java.util.Set;

/** Each grant retains its own owner/geography conjunction. No customer business-area codes. */
public record StoreAccessView(String tenantId, String userId, String action, long version,
        String employeeId, String employeeCode, String employeeName, String departmentId,
        Set<String> permissions, List<Grant> grants, boolean personalLimitEnabled, List<Area> personalAreas) {
    public StoreAccessView {
        permissions = Set.copyOf(permissions); grants = List.copyOf(grants); personalAreas = List.copyOf(personalAreas);
    }
    public record Area(String version, String code) {}
    public record Grant(String ownerScope, List<String> departmentIds, boolean descendants,
            boolean national, List<Area> areas) {
        public Grant { departmentIds = List.copyOf(departmentIds); areas = List.copyOf(areas); }
    }
}
