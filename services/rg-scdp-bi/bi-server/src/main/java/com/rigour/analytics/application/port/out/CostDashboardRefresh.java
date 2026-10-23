package com.rigour.analytics.application.port.out;

public interface CostDashboardRefresh {
    void refresh(String tenant);

    void failed(String tenant);
}
