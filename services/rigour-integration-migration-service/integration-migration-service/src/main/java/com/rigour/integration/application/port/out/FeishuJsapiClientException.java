package com.rigour.integration.application.port.out;

/** 飞书 JSSDK 上游调用的安全内部异常。 */
public final class FeishuJsapiClientException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public FeishuJsapiClientException(String code, int httpStatus) {
        super("飞书上游调用失败");
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
}
