package com.rigour.integration.api.v1.model;

import java.time.Instant;

/** 订货宝连接测试结果；只返回稳定错误码和 Token 到期时间，不返回 Token。 */
public record DhbConnectionTestResult(boolean success, String code,
                                             String message, Instant tokenExpiresAt) {
}
