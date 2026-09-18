package com.rigour.shared.context;

/** 测试专用可信身份装配器；生产代码仍只能由签名上下文过滤器建立身份。 */
public final class TestAuthorizationContext {
    private TestAuthorizationContext() {
    }

    public static void set(CallerIdentity identity) {
        AuthorizationContext.set(identity);
    }

    public static void clear() {
        AuthorizationContext.clear();
        TenantContext.clear();
    }
}
