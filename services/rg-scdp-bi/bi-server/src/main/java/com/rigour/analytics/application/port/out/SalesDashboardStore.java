package com.rigour.analytics.application.port.out;

import com.rigour.analytics.application.model.SalesDashboardData;
import com.rigour.analytics.application.model.SupplyDashboardFilter;

public interface SalesDashboardStore {
    SalesDashboardData query(String tenantId, SupplyDashboardFilter filter);
}
