package com.rigour.shared.core.api;

/**
 * 标准错误详情。
 * field 可为空，用于承载非字段级业务错误；reason 应使用稳定机器码而非展示文案。
 */
public record ApiErrorDetail(String field, String reason, String message) {
}
