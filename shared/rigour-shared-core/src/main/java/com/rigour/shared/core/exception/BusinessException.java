package com.rigour.shared.core.exception;

import com.rigour.shared.core.api.ApiErrorDetail;
import com.rigour.shared.core.api.ErrorCode;

import java.util.List;

/**
 * 可预期的业务异常。
 * 只携带稳定错误码和安全展示信息，不应在 message/details 中泄露内部 SQL、令牌或第三方回执。
 */
public final class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ApiErrorDetail> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), List.of());
    }

    public BusinessException(ErrorCode errorCode, String message, List<ApiErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<ApiErrorDetail> getDetails() {
        return details;
    }
}
