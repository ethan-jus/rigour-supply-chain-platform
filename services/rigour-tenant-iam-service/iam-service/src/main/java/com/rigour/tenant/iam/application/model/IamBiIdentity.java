package com.rigour.tenant.iam.application.model;

import java.util.List;
import java.util.UUID;

/** IAM 自有的精确身份与策略快照，不依赖传输层 DTO。 */
public record IamBiIdentity(UUID userId, String staffId, String staffCode,
                            long userSecurityVersion, long tenantPolicyVersion, List<Policy> policies,
                            List<ExternalBinding> externalBindings) {
    public IamBiIdentity {
        policies = List.copyOf(policies);
        externalBindings = List.copyOf(externalBindings);
    }
    public record Policy(String id, String roleCode, String scopeType) { }
    public record ExternalBinding(String sourceSystem, String sourceTenantKey, String sourceEmployeeId) { }
}
