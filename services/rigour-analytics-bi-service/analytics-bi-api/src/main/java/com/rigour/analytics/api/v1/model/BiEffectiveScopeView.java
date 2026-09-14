package com.rigour.analytics.api.v1.model;

import java.util.List;

/** 当前账号后端有效数据范围；默认值不等同于可以更改授权范围。 */
public record BiEffectiveScopeView(String accessLevel, String reasonCode, String reason,
                                   List<String> regionCodes, String employeeCode, String ownerStaffCode,
                                   String defaultRegionCode, String defaultOwnerStaffCode,
                                   boolean globalGovernance, List<String> unavailableSubjects) {
    public BiEffectiveScopeView {
        regionCodes = List.copyOf(regionCodes);
        unavailableSubjects = List.copyOf(unavailableSubjects);
    }
}
