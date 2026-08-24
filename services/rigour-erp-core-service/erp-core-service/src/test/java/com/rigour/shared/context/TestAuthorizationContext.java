package com.rigour.shared.context;

/** 测试专用身份上下文工具。 */
public final class TestAuthorizationContext {
    private TestAuthorizationContext() {
    }

    public static void set(CallerIdentity identity) {
        AuthorizationContext.set(identity);
    }

    public static void clear() {
        AuthorizationContext.clear();
    }
}
