package com.rigour.shared.audit;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 不可变审计事件。
 * attributes 只能包含完成审计所需的最小信息，禁止复制令牌、密码、录音正文等敏感内容。
 */
public record AuditEvent(
        String tenantId,
        String requestId,
        String operatorId,
        String action,
        String targetType,
        String targetId,
        Map<String, String> attributes,
        OffsetDateTime occurredAt
) {
    public AuditEvent {
        attributes = Map.copyOf(attributes);
    }
}
