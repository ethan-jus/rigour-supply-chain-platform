package com.rigour.shared.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 待投递领域事件契约。
 * payload 必须是版本化事件数据，不应直接序列化领域实体或携带无必要敏感字段。
 */
public record OutboxMessage(
        UUID eventId,
        String tenantId,
        String aggregateType,
        String aggregateId,
        String eventType,
        int eventVersion,
        String payload,
        OffsetDateTime occurredAt
) {
}
