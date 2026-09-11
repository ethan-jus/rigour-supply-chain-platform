package com.rigour.collaboration.application.port.out;

import java.util.UUID;

/** 在线连接事件发布端口；离线推送由 outbox 后续消费者处理。 */
public interface RealtimeEventPublisher {
    void publishToConversation(UUID tenantId, UUID conversationId, CollaborationEvent event);

    record CollaborationEvent(
            UUID eventId, UUID tenantId, String type, UUID conversationId,
            Long serverSeq, Object payload) {
    }
}
