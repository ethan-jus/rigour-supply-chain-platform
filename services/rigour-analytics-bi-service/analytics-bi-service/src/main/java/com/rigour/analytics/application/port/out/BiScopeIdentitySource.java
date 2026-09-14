package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.BiScopeSyncCommand;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** 管理员主动同步时访问 IAM/HR/CRM 版本化 API，读取精确关联证据。 */
public interface BiScopeIdentitySource {
    VerifiedIdentity verify(CallerIdentity administrator, BiScopeSyncCommand command);
    record VerifiedIdentity(String employeeCode, String ownerStaffCode, String iamBindingRef,
                            String hrEmployeeRef, String crmEmployeeRef, long userSecurityVersion,
                            List<BiDataScopeStore.Grant> grants) { }
}
