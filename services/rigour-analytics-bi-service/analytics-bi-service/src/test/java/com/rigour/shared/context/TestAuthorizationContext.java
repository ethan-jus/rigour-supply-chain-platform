package com.rigour.shared.context;

/** 测试专用授权上下文写入工具。 */
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
