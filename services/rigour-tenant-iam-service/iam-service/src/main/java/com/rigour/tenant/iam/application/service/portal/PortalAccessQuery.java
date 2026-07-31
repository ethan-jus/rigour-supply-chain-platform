package com.rigour.tenant.iam.application.service.portal;

import java.util.UUID;

/** 由已验签Access Token构造的Portal查询身份。 */
public record PortalAccessQuery(String principalScope, UUID principalId, UUID tenantId) {

    public PortalAccessQuery {
        if (principalId == null || !("PLATFORM".equals(principalScope) || "TENANT".equals(principalScope))) {
            throw new IllegalArgumentException("Portal principal is invalid");
        }
        if (("TENANT".equals(principalScope)) != (tenantId != null)) {
            throw new IllegalArgumentException("Portal tenant boundary is invalid");
        }
    }
}
