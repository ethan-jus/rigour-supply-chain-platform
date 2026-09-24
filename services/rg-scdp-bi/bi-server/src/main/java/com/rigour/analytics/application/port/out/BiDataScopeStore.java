package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;

import java.util.List;

/** 当前角色数据范围内的事实查询。 */
public interface BiDataScopeStore {
    boolean appObjectVisible(String tenantId, String city, String employee, String action);

    SupplyDashboardFilterOptionsView filterOptions(
            String tenantId, List<String> regions, String ownerStaffCode);
}
