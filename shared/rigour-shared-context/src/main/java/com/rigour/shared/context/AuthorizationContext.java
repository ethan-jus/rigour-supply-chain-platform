package com.rigour.shared.context;

import java.util.Set;
import java.util.Optional;

/** Gateway已验签并在线向IAM确认后的当前身份和权限；不替代领域数据范围校验。 */
public final class AuthorizationContext {
    private static final ThreadLocal<CallerIdentity> HOLDER = new ThreadLocal<>();

    private AuthorizationContext() {
    }

    static void set(CallerIdentity identity) { HOLDER.set(identity); }

    public static Optional<CallerIdentity> current() { return Optional.ofNullable(HOLDER.get()); }
    public static CallerIdentity requireCurrent() {
        return current().orElseThrow(() -> new AuthorizationDeniedException("authenticated-caller"));
    }
    public static String getPrincipalScope() { return value(identity -> identity.principalScope()); }
    public static String getPrincipalId() { return value(identity -> identity.principalId().toString()); }
    public static String getUserId() { return value(identity -> identity.userId() == null ? null : identity.userId().toString()); }
    public static String getPlatformUserId() { return value(identity -> identity.platformUserId() == null ? null : identity.platformUserId().toString()); }
    public static String getSessionId() { return value(identity -> identity.sessionId().toString()); }
    public static Set<String> getRoles() { return current().map(CallerIdentity::roles).orElseGet(Set::of); }
    public static Set<String> getPermissions() { return current().map(CallerIdentity::permissions).orElseGet(Set::of); }
    public static boolean hasPermission(String permission) {
        return getPermissions().contains("*:*:*") || getPermissions().contains(permission);
    }
    public static void requirePermission(String permission) {
        if (!hasPermission(permission)) throw new AuthorizationDeniedException(permission);
    }
    static void clear() { HOLDER.remove(); }
    private static String value(java.util.function.Function<CallerIdentity, String> resolver) {
        return current().map(resolver).orElse(null);
    }
}
