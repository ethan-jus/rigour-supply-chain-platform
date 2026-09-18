package com.rigour.tenant.iam.application.service.identity;

import java.util.UUID;

/** 由已验签Access Token构造的SCDP查询身份。 */
public record IdentityAccessQuery(String principalScope, UUID principalId, UUID tenantId) {

    public IdentityAccessQuery {
        if (principalId == null || !("PLATFORM".equals(principalScope) || "TENANT".equals(principalScope))) {
            throw new IllegalArgumentException("SCDP principal is invalid");
        }
        if (("TENANT".equals(principalScope)) != (tenantId != null)) {
            throw new IllegalArgumentException("SCDP tenant boundary is invalid");
        }
    }
}
