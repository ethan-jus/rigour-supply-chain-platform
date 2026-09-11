package com.rigour.integration.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Integration 编排器触发 HR 员工主档同步与来源人员解析的端口。 */
public interface HrDhbStaffSyncClient {

    StaffSyncResult sync(CallerIdentity caller, List<DhbStaffRow> rows);

    default List<ResolvedEmployee> resolve(CallerIdentity caller, String sourceTenantKey,
                                           List<String> sourceStaffIds) {
        return resolve(caller, sourceTenantKey, sourceStaffIds, List.of());
    }

    List<ResolvedEmployee> resolve(CallerIdentity caller, String sourceTenantKey,
                                   List<String> sourceStaffIds, List<String> sourceStaffNames);

    record DhbStaffRow(UUID connectorId, String sourceTenantKey, String sourceStaffId,
                       String staffType, String accountsName, String staffName,
                       String title, String branchName, String accountsMobile,
                       String about, String role, String inviteCode, String mobile,
                       String email, String qq, String status, Instant createDate,
                       Instant updateDate, String sourcePayloadHash, String sourcePayloadJson) {
    }

    record StaffSyncResult(int received, int created, int updated, int unchanged,
                           int failed, List<String> failureMessages) {
        public StaffSyncResult {
            failureMessages = failureMessages == null ? List.of() : List.copyOf(failureMessages);
        }
    }

    record ResolvedEmployee(String sourceTenantKey, String sourceStaffId, String employeeCode,
                            String employeeName, String employmentStatus) {
    }
}
