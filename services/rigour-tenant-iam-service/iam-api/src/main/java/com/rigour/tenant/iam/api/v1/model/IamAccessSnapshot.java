package com.rigour.tenant.iam.api.v1.model;

import java.util.Set;
import java.util.UUID;

/**
 * 供低频内部查询使用的IAM访问快照。
 *
 * <p>不包含密码、Token、数据库实体或员工业务档案。集合在构造时复制为不可变集合。</p>
 */
public record IamAccessSnapshot(
        UUID tenantId,
        UUID userId,
        long userSecurityVersion,
        long tenantPolicyVersion,
        Set<String> applicationCodes,
        Set<String> permissionCodes,
        Set<IamDataScopeGrant> dataScopes
) {
    public IamAccessSnapshot {
        applicationCodes = Set.copyOf(applicationCodes);
        permissionCodes = Set.copyOf(permissionCodes);
        dataScopes = Set.copyOf(dataScopes);
    }
}
