package com.rigour.shared.context;

/**
 * 当前线程的租户上下文。
 * 该值用于租户隔离条件，不代表调用方已经通过授权；领域服务仍必须执行权限和 DataScope 校验。
 */
public final class TenantContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        HOLDER.set(tenantId);
    }

    public static String getTenantId() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
