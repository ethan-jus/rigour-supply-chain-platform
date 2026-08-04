package com.rigour.integration.infrastructure.dinghuobao;

/** 订货宝调用失败；只携带脱敏后的稳定错误信息，不携带令牌、密码或原始回执。 */
public final class DinghuobaoClientException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final Integer httpStatus;
    private final Integer providerStatus;

    public DinghuobaoClientException(String code, String message, boolean retryable,
                                     Integer httpStatus, Integer providerStatus) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.providerStatus = providerStatus;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Integer providerStatus() {
        return providerStatus;
    }
}
