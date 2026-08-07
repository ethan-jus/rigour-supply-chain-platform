package com.rigour.sales.infrastructure.persistence;

import com.rigour.shared.outbox.OutboxMessage;
import com.rigour.shared.outbox.OutboxStore;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Sales Work 事务内 Outbox 写入；投递器和重试策略仍由本服务后续补齐。 */
@Repository
public class JdbcSalesOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbc;

    public JdbcSalesOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OutboxMessage message) {
        Long version = jdbc.queryForObject("""
                SELECT version FROM sales_work_day WHERE tenant_id=? AND id=?
                """, Long.class, bin(UUID.fromString(message.tenantId())), bin(UUID.fromString(message.aggregateId())));
        String payload = message.payload() == null || message.payload().isBlank() ? "{}" : message.payload();
        jdbc.update("""
                INSERT INTO sales_outbox_event
                    (id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
                     event_type, event_version, payload_json, occurred_at, publish_status,
                     retry_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, UTC_TIMESTAMP(6))
                """, bin(message.eventId()), bin(UUID.fromString(message.tenantId())),
                message.aggregateType(), bin(UUID.fromString(message.aggregateId())),
                version == null ? 0L : version, message.eventType(), message.eventVersion(),
                payload, timestamp(message.occurredAt()));
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }
}
