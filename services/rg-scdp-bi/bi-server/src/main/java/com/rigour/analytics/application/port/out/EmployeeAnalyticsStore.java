package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.EmployeeAnalyticsView.Employee;
import java.time.Instant;
import java.util.List;

/** 员工投影端口；刷新可读 HR，普通分析只读 BI 本地表。 */
public interface EmployeeAnalyticsStore {
    List<String> tenantIds();
    SupplyDashboardStore.SourceRefreshResult refresh(String tenantId, Instant syncedAt);
    Snapshot read(String tenantId, Instant from, Instant to, String regionCode, String employeeCode);

    record Snapshot(Instant syncedAt, List<Row> rows) { }
    record Row(Employee employee, String regionCode) { }
}
