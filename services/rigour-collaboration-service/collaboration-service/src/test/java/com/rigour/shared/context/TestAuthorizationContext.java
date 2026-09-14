package com.rigour.shared.context;

import java.util.Set;
import java.util.UUID;

/** 测试中设置可信调用人上下文。 */
public final class TestAuthorizationContext {
    private TestAuthorizationContext() {
    }

    public static void tenant(UUID tenantId, UUID userId) {
        tenant(tenantId, userId, Set.of("*:*:*"));
    }

    public static void tenant(UUID tenantId, UUID userId, Set<String> permissions) {
        AuthorizationContext.set(new CallerIdentity("TENANT", userId, tenantId, userId, null,
                UUID.randomUUID(), 1, 1, 1, Set.of("employee"), permissions));
        TenantContext.setTenantId(tenantId.toString());
    }

    public static void clear() {
        AuthorizationContext.clear();
        TenantContext.clear();
    }
}
