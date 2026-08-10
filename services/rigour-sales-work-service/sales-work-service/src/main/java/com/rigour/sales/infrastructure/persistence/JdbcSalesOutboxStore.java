package com.rigour.sales.infrastructure.persistence;

import com.rigour.shared.outbox.OutboxMessage;
import com.rigour.shared.outbox.OutboxStore;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
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
        Long version = aggregateVersion(message);
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

    /**
     * 聚合版本按聚合类型路由到对应表查询；聚合行不存在或未知类型时记 0，
     * 不能把 Outbox 写入失败升级为业务 500（此前 SALES_VISIT 误查 sales_work_day）。
     */
    private Long aggregateVersion(OutboxMessage message) {
        String table = switch (message.aggregateType()) {
            case "SALES_WORK_DAY" -> "sales_work_day";
            case "SALES_VISIT" -> "sales_visit";
            default -> "";
        };
        if (table.isEmpty()) {
            return 0L;
        }
        List<Long> rows = jdbc.query("SELECT version FROM " + table + " WHERE tenant_id=? AND id=?",
                (rs, row) -> rs.getLong("version"),
                bin(UUID.fromString(message.tenantId())), bin(UUID.fromString(message.aggregateId())));
        return rows.stream().findFirst().orElse(0L);
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }
}
