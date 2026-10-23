package com.rigour.analytics.application.service;

/** 可预期的费用看板状态；只携带可公开的业务提示，不输出源系统错误正文。 */
public final class CostDashboardFailure extends RuntimeException {
    private final String code;
    private final int status;

    public CostDashboardFailure(String code, int status, String message) {
        this(code, status, message, null);
    }

    public CostDashboardFailure(String code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static CostDashboardFailure expired() {
        return new CostDashboardFailure("SNAPSHOT_EXPIRED", 409, "费用快照已过期，请刷新整个看板后重试");
    }
}
