package com.rigour.tenant.iam.application.port.out;

/** 飞书身份提供方的安全失败分类；不携带授权码、令牌或原始上游响应。 */
public final class FeishuIdentityProviderException extends RuntimeException {

    private final Reason reason;
    private final long providerCode;
    private final int httpStatus;

    public FeishuIdentityProviderException(Reason reason, long providerCode, int httpStatus) {
        super(reason.name());
        this.reason = reason;
        this.providerCode = providerCode;
        this.httpStatus = httpStatus;
    }

    public Reason reason() { return reason; }
    public long providerCode() { return providerCode; }
    public int httpStatus() { return httpStatus; }

    public enum Reason {
        INVALID_CODE,
        CONFIG_INVALID,
        UPSTREAM_FAILED
    }
}
