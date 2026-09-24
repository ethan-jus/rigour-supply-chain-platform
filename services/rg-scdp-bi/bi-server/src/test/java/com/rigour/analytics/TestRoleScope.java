package com.rigour.analytics;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import com.rigour.tenant.iam.client.SupplyAuthorizationContext;

import java.util.*;

/** 测试显式提供当前 IAM 角色配置，不再用旧登录角色推断数据范围。 */
public final class TestRoleScope {
    private static final ThreadLocal<SupplyAuthorizationContext> CONTEXT = new ThreadLocal<>();

    public static void set(CallerIdentity caller) {
        set(caller, "ALL");
    }

    public static void set(CallerIdentity caller, String mode) {
        clear();
        TestAuthorizationContext.set(caller);
        if (caller.tenantId() == null) return;
        var all = new Limit("ALL", List.of());
        var department = "DEPARTMENT".equals(mode) ? new Limit("SPECIFIED", List.of("10")) : all;
        java.util.function.Function<String, SupplyAuthorizationView> policy =
                action ->
                        new SupplyAuthorizationView(
                                "ACTIVE",
                                caller.tenantId(),
                                caller.userId(),
                                "E1",
                                1,
                                1,
                                1,
                                1,
                                caller.permissions(),
                                action,
                                caller.permissions().contains(action)
                                        || caller.permissions().contains("*:*:*"),
                                List.of(
                                        new Clause(
                                                UUID.randomUUID(),
                                                "ANALYTICS",
                                                mode,
                                                department,
                                                all,
                                                all,
                                                false)),
                                all,
                                all);
        CONTEXT.set(
                SupplyAuthorizationContext.open(
                        (identity, action) -> policy.apply(action),
                        caller,
                        policy.apply("analytics:dashboard:read")));
    }

    public static void clear() {
        var context = CONTEXT.get();
        if (context != null) context.close();
        CONTEXT.remove();
        TestAuthorizationContext.clear();
    }
}
