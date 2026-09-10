package com.rigour.integration.application.port.out;

/** 飞书 Base/附件 API 调用异常；错误码可落到行级导入结果。 */
public final class FeishuBitableClientException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public FeishuBitableClientException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
