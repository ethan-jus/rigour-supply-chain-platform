package com.rigour.tenant.iam.api.v1.model;

import java.util.List;
import java.util.UUID;

/** BI 仅消费的账号绑定与现有数据策略，不包含员工姓名、手机或认证凭据。 */
public record IamBiIdentityView(UUID userId, String staffId, String staffCode,
                               long userSecurityVersion, long tenantPolicyVersion, List<Policy> policies,
                               List<ExternalBinding> externalBindings) {
    public record Policy(String id, String roleCode, String scopeType) { }
    public record ExternalBinding(String sourceSystem, String sourceTenantKey, String sourceEmployeeId) { }
}
