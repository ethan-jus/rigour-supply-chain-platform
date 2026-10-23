package com.rigour.analytics.api.controller;

import com.rigour.analytics.application.service.CostDashboardFailure;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ApiResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** 仅处理本看板的可预期失败，权限、校验及未知系统错误仍使用平台统一处理器。 */
@Order(-10)
@RestControllerAdvice(assignableTypes = CostDashboardController.class)
public final class CostDashboardExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(CostDashboardExceptionHandler.class);

    @ExceptionHandler(CostDashboardFailure.class)
    public ResponseEntity<ApiResponse<Void>> failure(CostDashboardFailure failure) {
        log.warn(
                "cost_dashboard_request_failed requestId={} code={}",
                RequestContext.getRequestId(),
                failure.code());
        return ResponseEntity.status(failure.status())
                .body(ApiResponse.error(failure.code(), failure.getMessage(), List.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalidQuery(IllegalArgumentException failure) {
        log.info("cost_dashboard_query_rejected requestId={}", RequestContext.getRequestId());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("BAD_REQUEST", "查询条件无效，请检查年月、范围与筛选组合", List.of()));
    }
}
