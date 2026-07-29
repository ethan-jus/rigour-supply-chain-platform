package com.rigour.shared.core.api;

import org.springframework.http.HttpStatus;

/**
 * 平台通用错误码。
 * 领域错误码仍应使用 DOMAIN_REASON 命名，并明确对应的 HTTP 协议状态。
 */
public enum ErrorCode {

    BAD_REQUEST("BAD_REQUEST", "请求参数无效", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "未认证或令牌无效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "无权执行当前操作", HttpStatus.FORBIDDEN),
    NOT_FOUND("NOT_FOUND", "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT("CONFLICT", "资源状态冲突", HttpStatus.CONFLICT),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
