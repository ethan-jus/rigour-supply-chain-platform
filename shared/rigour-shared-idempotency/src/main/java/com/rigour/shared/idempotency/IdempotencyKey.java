package com.rigour.shared.idempotency;

/**
 * 租户内幂等键。
 * operation 用于隔离不同业务命令，value 来自受控请求头或来源系统稳定标识。
 */
public record IdempotencyKey(String tenantId, String operation, String value) {

    public IdempotencyKey {
        if (isBlank(tenantId) || isBlank(operation) || isBlank(value)) {
            throw new IllegalArgumentException("tenantId、operation 和 value 均不能为空");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
