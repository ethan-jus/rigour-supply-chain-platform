package com.rigour.shared.context;

/** 测试专用调用身份。 */
public final class TestAuthorizationContext {
    private TestAuthorizationContext() {}

    public static void set(CallerIdentity identity) {
        AuthorizationContext.set(identity);
    }

    public static void clear() {
        AuthorizationContext.clear();
    }
}
