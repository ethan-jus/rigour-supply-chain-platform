package com.rigour.shared.context;

import java.util.Optional;
import java.util.Set;

/** Gateway已验签并在线向IAM确认后的当前身份和权限；不替代领域数据范围校验。 */
public final class AuthorizationContext {
    private static final ThreadLocal<CallerIdentity> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> APPLICATION = new ThreadLocal<>();

    private AuthorizationContext() {}

    static void set(CallerIdentity identity) {
        HOLDER.set(identity);
    }

    public static Optional<CallerIdentity> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static CallerIdentity requireCurrent() {
        return current()
                .orElseThrow(() -> new AuthorizationDeniedException("authenticated-caller"));
    }

    public static String getPrincipalScope() {
        return value(identity -> identity.principalScope());
    }

    public static String getPrincipalId() {
        return value(identity -> identity.principalId().toString());
    }

    public static String getUserId() {
        return value(identity -> identity.userId() == null ? null : identity.userId().toString());
    }

    public static String getPlatformUserId() {
        return value(
                identity ->
                        identity.platformUserId() == null
                                ? null
                                : identity.platformUserId().toString());
    }

    public static String getSessionId() {
        return value(identity -> identity.sessionId().toString());
    }

    public static Set<String> getRoles() {
        return current().map(CallerIdentity::roles).orElseGet(Set::of);
    }

    public static Set<String> getPermissions() {
        return current().map(CallerIdentity::permissions).orElseGet(Set::of);
    }

    public static boolean hasPermission(String permission) {
        return (APPLICATION.get() == null && getPermissions().contains("*:*:*"))
                || getPermissions().contains(permission);
    }

    public static void requirePermission(String permission) {
        if (!hasPermission(permission)) throw new AuthorizationDeniedException(permission);
    }

    /** 只供完成权威应用授权核验的入站适配器使用；作用域退出恢复原身份，禁止合并全局通配权限。 */
    public static PermissionScope useVerifiedApplication(
            String application, Set<String> permissions) {
        if (application == null
                || application.isBlank()
                || permissions == null
                || permissions.contains("*:*:*"))
            throw new IllegalArgumentException("应用授权必须为明确权限集合");
        CallerIdentity prior = requireCurrent();
        String previousApplication = APPLICATION.get();
        HOLDER.set(
                new CallerIdentity(
                        prior.principalScope(),
                        prior.principalId(),
                        prior.tenantId(),
                        prior.userId(),
                        prior.platformUserId(),
                        prior.sessionId(),
                        prior.sessionVersion(),
                        prior.userSecurityVersion(),
                        prior.tenantPolicyVersion(),
                        Set.of(),
                        permissions));
        APPLICATION.set(application);
        return new PermissionScope(prior, previousApplication);
    }

    public static final class PermissionScope implements AutoCloseable {
        private final CallerIdentity prior;
        private final String previousApplication;
        private boolean closed;

        private PermissionScope(CallerIdentity prior, String previousApplication) {
            this.prior = prior;
            this.previousApplication = previousApplication;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            HOLDER.set(prior);
            if (previousApplication == null) APPLICATION.remove();
            else APPLICATION.set(previousApplication);
        }
    }

    static void clear() {
        HOLDER.remove();
        APPLICATION.remove();
    }

    private static String value(java.util.function.Function<CallerIdentity, String> resolver) {
        return current().map(resolver).orElse(null);
    }
}
