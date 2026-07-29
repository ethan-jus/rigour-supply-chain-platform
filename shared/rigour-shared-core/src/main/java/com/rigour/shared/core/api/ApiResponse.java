package com.rigour.shared.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rigour.shared.context.RequestContext;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 跨仓库统一响应契约。
 * requestId 在创建响应时从 RequestContext 读取，保证响应体与响应头使用同一调用链标识。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        List<ApiErrorDetail> details,
        String requestId,
        OffsetDateTime timestamp
) {

    public ApiResponse {
        details = details == null ? null : List.copyOf(details);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data, null,
                RequestContext.getRequestId(), OffsetDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage(), List.of());
    }

    public static ApiResponse<Void> error(String code, String message, List<ApiErrorDetail> details) {
        return new ApiResponse<>(code, message, null, details,
                RequestContext.getRequestId(), OffsetDateTime.now());
    }
}
