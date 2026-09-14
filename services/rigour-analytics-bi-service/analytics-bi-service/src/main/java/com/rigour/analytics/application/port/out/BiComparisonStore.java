package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.BiComparisonView.Values;
import com.rigour.analytics.api.v1.model.BiComparisonView.City;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import java.util.List;

/** 在同一只读事务中查询全量订单汇总及城市比较，不从 Top N 反推总额。 */
public interface BiComparisonStore {
    Snapshot compare(String tenantId, SupplyDashboardFilter current, SupplyDashboardFilter previous);
    record Snapshot(Values current, Values previous, List<City> cities) { }
}
