package com.rigour.integration.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** Integration 按本系统档案解析订货宝人员的端口。 */
public interface HrDhbStaffSyncClient {

    default List<ResolvedEmployee> resolve(CallerIdentity caller, String sourceTenantKey,
                                           List<String> sourceStaffIds) {
        return resolve(caller, sourceTenantKey, sourceStaffIds, List.of());
    }

    List<ResolvedEmployee> resolve(CallerIdentity caller, String sourceTenantKey,
                                   List<String> sourceStaffIds, List<String> sourceStaffNames);

    record ResolvedEmployee(String sourceTenantKey, String sourceStaffId, String employeeCode,
                            String employeeName, String employmentStatus) {
    }
}
