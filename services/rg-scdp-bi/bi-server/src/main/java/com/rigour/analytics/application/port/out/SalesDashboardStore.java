package com.rigour.analytics.application.port.out;

import com.rigour.analytics.application.model.SalesDashboardData;
import com.rigour.analytics.application.model.SalesDashboardSummary;
import java.util.List;
import com.rigour.analytics.application.model.SupplyDashboardFilter;

public interface SalesDashboardStore {
    enum Projection { FULL, RANKING, COMPARISON }

    SalesDashboardData query(String tenantId, SupplyDashboardFilter filter);
    default SalesDashboardData query(String tenantId, SupplyDashboardFilter filter, Projection projection) {
        return query(tenantId, filter);
    }
    SalesDashboardSummary summary(String tenantId, SupplyDashboardFilter filter, List<String> owners);
}
