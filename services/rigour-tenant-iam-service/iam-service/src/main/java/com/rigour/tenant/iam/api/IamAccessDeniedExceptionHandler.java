package com.rigour.tenant.iam.api;

import com.rigour.shared.context.AuthenticationFailureCodes;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将IAM内部Spring Security授权拒绝统一映射为稳定403，避免落入通用500。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IamAccessDeniedExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IamAccessDeniedExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        log.warn("IAM授权校验拒绝请求 requestId={}", RequestContext.getRequestId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(AuthenticationFailureCodes.IAM_FORBIDDEN,
                        "没有访问该资源的权限", List.of()));
    }
}
