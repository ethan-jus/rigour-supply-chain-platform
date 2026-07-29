package com.rigour.shared.idempotency;

import java.time.OffsetDateTime;

/**
 * 幂等执行状态快照。
 * responsePayload 是否保存以及保存多久由领域服务根据敏感性和重放需求决定。
 */
public record IdempotencyRecord(
        IdempotencyKey key,
        Status status,
        String responsePayload,
        OffsetDateTime expiresAt
) {
    public enum Status {
        PROCESSING,
        COMPLETED
    }
}
