package com.rigour.order.application.service;

import com.rigour.order.api.v1.SettingsOperationAuditApi.Page;
import com.rigour.order.application.port.out.SettingsOperationAuditStore;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationClient;

import org.springframework.stereotype.Service;

@Service
public final class SettingsOperationAuditService {
    private final SettingsOperationAuditStore store;
    private final SupplyAuthorizationClient permissions;

    public SettingsOperationAuditService(
            SettingsOperationAuditStore store, SupplyAuthorizationClient permissions) {
        this.store = store;
        this.permissions = permissions;
    }

    public Page audits(String action, String keyword, int page, int size) {
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope())
                || !permissions.authorization(actor, "supply:audit:read").functionAllowed())
            throw new AuthorizationDeniedException("supply:audit:read");
        return store.audits(
                actor.tenantId().toString(),
                action,
                keyword,
                Math.max(1, page),
                Math.max(1, Math.min(100, size)));
    }
}
