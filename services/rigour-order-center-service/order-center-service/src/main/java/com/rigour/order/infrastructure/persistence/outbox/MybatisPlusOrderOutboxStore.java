package com.rigour.order.infrastructure.persistence.outbox;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rigour.order.infrastructure.persistence.entity.OrderOutboxEventEntity;
import com.rigour.order.infrastructure.persistence.mapper.OrderOutboxEventMapper;
import com.rigour.shared.outbox.OutboxMessage;
import com.rigour.shared.outbox.OutboxStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 订单领域事件以同库同事务方式写入 Outbox。当前只负责可靠落库，消息投递器后续独立实现。 */
@Component
public final class MybatisPlusOrderOutboxStore implements OutboxStore {
    private final OrderOutboxEventMapper mapper;

    public MybatisPlusOrderOutboxStore(OrderOutboxEventMapper mapper) { this.mapper = mapper; }

    @Override
    public void append(OutboxMessage message) {
        OrderOutboxEventEntity entity = new OrderOutboxEventEntity();
        entity.id = message.eventId().toString();
        entity.tenantId = message.tenantId();
        entity.aggregateType = message.aggregateType();
        entity.aggregateId = message.aggregateId();
        entity.eventType = message.eventType();
        entity.eventVersion = message.eventVersion();
        entity.eventKey = message.aggregateId() + ":" + message.eventType() + ":" + hash(message.payload());
        Long existing = mapper.selectCount(Wrappers.<OrderOutboxEventEntity>query()
                .eq("tenant_id", entity.tenantId).eq("event_key", entity.eventKey));
        if (existing != null && existing > 0) return;
        entity.payloadJson = message.payload();
        entity.status = "PENDING";
        entity.attempts = 0;
        entity.availableAt = LocalDateTime.ofInstant(message.occurredAt().toInstant(), ZoneOffset.UTC);
        entity.createdAt = entity.availableAt;
        entity.updatedAt = entity.availableAt;
        mapper.insert(entity);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK缺少SHA-256", error);
        }
    }
}
