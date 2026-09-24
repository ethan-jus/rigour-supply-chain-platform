package com.rigour.shared.core.exception;

/** 明确可向用户展示的业务拒绝原因；禁止传入底层异常、SQL 或敏感数据。 */
public final class RequestValidationException extends IllegalArgumentException {
    public RequestValidationException(String message) {
        super(message);
    }
}
