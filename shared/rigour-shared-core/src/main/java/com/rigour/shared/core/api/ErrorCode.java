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
    SALES_IDENTITY_UNBOUND("SALES_IDENTITY_UNBOUND", "销售身份尚未完成绑定", HttpStatus.FORBIDDEN),
    SALES_PROFILE_INACTIVE("SALES_PROFILE_INACTIVE", "销售业务身份未生效", HttpStatus.FORBIDDEN),
    SALES_POLICY_NOT_FOUND("SALES_POLICY_NOT_FOUND", "当前没有生效的销售外勤规则", HttpStatus.CONFLICT),
    SALES_WORK_DAY_NOT_FOUND("SALES_WORK_DAY_NOT_FOUND", "销售工作日不存在", HttpStatus.NOT_FOUND),
    SALES_WORK_DAY_ALREADY_ACTIVE("SALES_WORK_DAY_ALREADY_ACTIVE", "当前业务日已有进行中的销售工作日", HttpStatus.CONFLICT),
    SALES_WORK_DAY_INVALID_STATE("SALES_WORK_DAY_INVALID_STATE", "销售工作日状态不允许当前操作", HttpStatus.CONFLICT),
    SALES_IDEMPOTENCY_CONFLICT("SALES_IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求", HttpStatus.CONFLICT),
    SALES_IDEMPOTENCY_IN_PROGRESS("SALES_IDEMPOTENCY_IN_PROGRESS", "相同幂等请求正在处理中", HttpStatus.CONFLICT),
    SALES_LOCATION_INVALID("SALES_LOCATION_INVALID", "定位证据无效", HttpStatus.BAD_REQUEST),
    SALES_LOCATION_BATCH_TOO_LARGE("SALES_LOCATION_BATCH_TOO_LARGE", "定位批次超过服务端限制", HttpStatus.BAD_REQUEST),
    SALES_CHECK_IN_OUTSIDE_WINDOW("SALES_CHECK_IN_OUTSIDE_WINDOW", "当前不在签到时间窗口内", HttpStatus.CONFLICT),
    SALES_CHECK_OUT_OUTSIDE_WINDOW("SALES_CHECK_OUT_OUTSIDE_WINDOW", "当前不在签退时间窗口内", HttpStatus.CONFLICT),
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
