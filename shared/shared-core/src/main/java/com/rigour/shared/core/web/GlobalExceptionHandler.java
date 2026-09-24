package com.rigour.shared.core.web;

import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ApiErrorDetail;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.core.exception.StateConflictException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 将框架异常和业务异常格式化为统一错误契约。
 * 未知异常只返回稳定通用文案，完整堆栈仅写服务端日志，避免向客户端暴露内部实现。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 框架决定协议状态及 Allow/Retry-After 等头；这里只统一安全的响应体。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        ApiResponse<Void> failure;
        if (exception instanceof MethodArgumentNotValidException validation) {
            failure = handleMethodArgumentNotValid(validation).getBody();
        } else {
            ErrorCode code = switch (status.value()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 401 -> ErrorCode.UNAUTHORIZED;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
                case 406 -> ErrorCode.NOT_ACCEPTABLE;
                case 409 -> ErrorCode.CONFLICT;
                case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
                case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
                case 422 -> ErrorCode.VALIDATION_FAILED;
                case 429 -> ErrorCode.RATE_LIMITED;
                case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
                default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST;
            };
            failure = ApiResponse.error(code);
        }
        if (status.is5xxServerError()) {
            if (isClientDisconnect(exception)) return null;
            log.error("框架处理请求失败 requestId={}",
                    com.rigour.shared.context.RequestContext.getRequestId(), exception);
        }
        return super.handleExceptionInternal(exception, failure, headers, status, request);
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiResponse<Void>> handleRequestValidation(RequestValidationException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_FAILED", exception.getMessage(), List.of()));
    }

    @ExceptionHandler(StateConflictException.class)
    ResponseEntity<ApiResponse<Void>> handleStateConflict(StateConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("CONFLICT", exception.getMessage(), List.of()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                "CONFLICT", "编码或关联关系已存在，请检查后重试", List.of()));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getCode(), exception.getMessage(), exception.getDetails()));
    }

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

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        if (isClientDisconnect(exception)) {
            log.info("客户端在响应写入过程中已断开，停止写入错误响应 requestId={} exceptionType={}",
                    com.rigour.shared.context.RequestContext.getRequestId(),
                    exception.getClass().getSimpleName());
            return null;
        }
        log.error("未处理的服务异常 requestId={}",
                com.rigour.shared.context.RequestContext.getRequestId(), exception);
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
