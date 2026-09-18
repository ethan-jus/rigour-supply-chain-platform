package com.rigour.merchant.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** CRM 读取 HR 员工主档的端口；CRM 不维护员工主档。 */
public interface HrEmployeeDirectoryClient {

    List<ResolvedEmployee> resolveDinghuobaoEmployees(CallerIdentity caller,
                                                      String sourceTenantKey,
                                                      List<String> sourceStaffIds);

    record ResolvedEmployee(String sourceTenantKey, String sourceStaffId,
                            String employeeCode, String employeeName,
                            String employmentStatus) {
    }
}
