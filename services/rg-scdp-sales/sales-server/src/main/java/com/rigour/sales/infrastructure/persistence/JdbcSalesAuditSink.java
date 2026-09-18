package com.rigour.sales.infrastructure.persistence;

import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Sales Work 审计落库；不记录经纬度、Token、录音正文或第三方原始响应。 */
@Repository
public class JdbcSalesAuditSink implements AuditSink {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSalesAuditSink(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.update("""
                INSERT INTO sales_audit_log
                    (id, tenant_id, actor_id, actor_type, action_code, target_type, target_id,
                     result, request_id, occurred_at, detail_json)
                VALUES (?, ?, ?, 'TENANT_USER', ?, ?, ?, 'SUCCESS', ?, ?, ?)
                """, bin(UUID.randomUUID()), bin(UUID.fromString(event.tenantId())),
                event.operatorId() == null ? null : bin(UUID.fromString(event.operatorId())),
                event.action(), event.targetType(), event.targetId() == null ? null : bin(UUID.fromString(event.targetId())),
                event.requestId(), timestamp(event.occurredAt()), writeJson(event.attributes()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException error) {
            throw new IllegalStateException("销售审计详情序列化失败", error);
        }
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }
}
