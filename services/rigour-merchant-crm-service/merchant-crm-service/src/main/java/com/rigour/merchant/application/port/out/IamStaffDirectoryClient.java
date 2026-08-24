package com.rigour.merchant.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** CRM 读取 IAM 人员中心的端口；CRM 不维护员工主档。 */
public interface IamStaffDirectoryClient {

    List<ResolvedStaff> resolveDinghuobaoStaff(CallerIdentity caller,
                                               String sourceTenantKey,
                                               List<String> sourceStaffIds);

    record ResolvedStaff(String sourceTenantKey, String sourceStaffId, UUID staffId,
                         String staffCode, String staffName, UUID userId,
                         String username, String userDisplayName,
                         UUID primaryOrganizationId, String primaryOrganizationName,
                         UUID primaryPositionId, String primaryPositionName,
                         String employmentStatus, String sourceStaffType,
                         String sourceRole, String sourcePresence,
                         Instant lastSeenAt) {
    }
}
