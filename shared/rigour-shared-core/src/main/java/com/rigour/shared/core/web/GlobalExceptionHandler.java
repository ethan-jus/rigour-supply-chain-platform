package com.rigour.shared.core.web;

import com.rigour.shared.core.api.ApiErrorDetail;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.context.AuthorizationDeniedException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 将框架异常和业务异常格式化为统一错误契约。
 * 未知异常只返回稳定通用文案，完整堆栈仅写服务端日志，避免向客户端暴露内部实现。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getCode(), exception.getMessage(), exception.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        List<ApiErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorDetail(error.getField(), "VALIDATION", error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_FAILED", "参数校验失败", details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        List<ApiErrorDetail> details = exception.getConstraintViolations().stream()
                .map(violation -> new ApiErrorDetail(
                        violation.getPropertyPath().toString(), "VALIDATION", violation.getMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_FAILED", "参数校验失败", details));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handleAuthorizationDenied(AuthorizationDeniedException exception) {
        log.warn("授权校验拒绝请求 requestId={}", com.rigour.shared.context.RequestContext.getRequestId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("IAM_FORBIDDEN", "没有访问该资源的权限", List.of()));
    }

    @ExceptionHandler(ResourceAccessException.class)
    ResponseEntity<ApiResponse<Void>> handleResourceAccess(ResourceAccessException exception) {
        log.warn("下游服务暂不可用 requestId={} reason={}",
                com.rigour.shared.context.RequestContext.getRequestId(), exception.getMessage());
        return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        if (isClientDisconnect(exception)) {
            log.info("客户端在响应写入过程中已断开，停止写入错误响应 requestId={} exceptionType={}",
                    com.rigour.shared.context.RequestContext.getRequestId(),
                    exception.getClass().getSimpleName());
            return null;
        }
        log.error("未处理的服务异常", exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    private static boolean isClientDisconnect(Throwable exception) {
        boolean responseWriteFailure = exception instanceof HttpMessageNotWritableException;
        for (Throwable current = exception; current != null; current = current.getCause()) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.endsWith("ClientAbortException")) {
                return true;
            }
            if (responseWriteFailure && current instanceof java.io.IOException && message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset by peer")
                        || normalized.contains("connection reset")) {
                    return true;
                }
            }
        }
        return false;
    }
}
