package com.rigour.collaboration.infrastructure.realtime;

import com.rigour.collaboration.application.port.out.CollaborationStore;
import com.rigour.collaboration.application.port.out.RealtimeEventPublisher;
import com.rigour.collaboration.application.port.out.RealtimeEventPublisher.CollaborationEvent;
import com.rigour.shared.context.RequestHeaders;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/** WebSocket 在线事件通道；只做会话广播，不承载消息事实。 */
@Component
public class CollaborationWebSocketHandler extends TextWebSocketHandler implements RealtimeEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(CollaborationWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final CollaborationStore store;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> conversationSubscriptions = new ConcurrentHashMap<>();

    public CollaborationWebSocketHandler(ObjectMapper objectMapper, CollaborationStore store) {
        this.objectMapper = objectMapper;
        this.store = store;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> body = objectMapper.readValue(message.getPayload(), Map.class);
        if (!"subscribe".equals(body.get("type"))) return;
        UUID tenantId = headerUuid(session, RequestHeaders.TENANT_ID);
        UUID userId = headerUuid(session, RequestHeaders.USER_ID);
        if (!hasPermission(session, "collaboration:im:use")) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("missing collaboration permission"));
            return;
        }
        UUID conversationId = UUID.fromString(String.valueOf(body.get("conversationId")));
        if (store.findMember(tenantId, conversationId, userId).filter(CollaborationStore.MemberRecord::active).isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("not conversation member"));
            return;
        }
        conversationSubscriptions.computeIfAbsent(key(tenantId, conversationId), ignored -> ConcurrentHashMap.newKeySet())
                .add(session.getId());
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "subscribed",
                "tenantId", tenantId.toString(),
                "conversationId", conversationId.toString()
        ))));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        conversationSubscriptions.values().forEach(ids -> ids.remove(session.getId()));
    }

    @Override
    public void publishToConversation(UUID tenantId, UUID conversationId, CollaborationEvent event) {
        Set<String> sessionIds = conversationSubscriptions.getOrDefault(key(tenantId, conversationId), Set.of());
        if (sessionIds.isEmpty()) return;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            log.warn("Collaboration websocket event serialization failed: eventType={}", event.type());
            return;
        }
        for (String sessionId : sessionIds) {
            WebSocketSession session = sessions.get(sessionId);
            if (session == null || !session.isOpen()) continue;
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (IOException ex) {
                log.debug("Collaboration websocket send failed: sessionId={}", sessionId, ex);
            }
        }
    }

    private static UUID headerUuid(WebSocketSession session, String name) {
        String value = session.getHandshakeHeaders().getFirst(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return UUID.fromString(value);
    }

    private static boolean hasPermission(WebSocketSession session, String permission) {
        String value = session.getHandshakeHeaders().getFirst(RequestHeaders.PERMISSIONS);
        if (value == null || value.isBlank()) return false;
        Set<String> permissions = java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
        return permissions.contains("*:*:*") || permissions.contains(permission);
    }

    private static String key(UUID tenantId, UUID conversationId) {
        return tenantId + ":" + conversationId;
    }
}
