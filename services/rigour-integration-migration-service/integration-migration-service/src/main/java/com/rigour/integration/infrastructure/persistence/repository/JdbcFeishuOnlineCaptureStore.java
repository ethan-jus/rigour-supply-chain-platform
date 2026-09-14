package com.rigour.integration.infrastructure.persistence.repository;

import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureSummary;
import com.rigour.integration.application.port.out.FeishuOnlineCaptureStore;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Integration 私有证据表只追加；UTC DATETIME6 不经过 JVM 默认时区转换。 */
@Repository
public class JdbcFeishuOnlineCaptureStore implements FeishuOnlineCaptureStore {
    private final JdbcTemplate jdbc;
    public JdbcFeishuOnlineCaptureStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void save(UUID tenantId, UUID actorId, CaptureSummary summary, String payloadJson) {
        if (tenantId == null || actorId == null || summary == null || !summary.complete()
                || summary.startedAt() == null || summary.completedAt() == null
                || summary.completedAt().isBefore(summary.startedAt()) || summary.pageCount() < 1) {
            throw new IllegalArgumentException("禁止保存身份缺失或未完成的飞书采集证据");
        }
        jdbc.update("""
                INSERT INTO integration_feishu_online_capture
                  (id, tenant_id, actor_id, source_id, source_name, source_url, started_at, completed_at,
                   complete, filtered, checksum, record_count, page_count, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(summary.id()), bytes(tenantId), bytes(actorId), summary.sourceId(),
                summary.sourceName(), summary.sourceUrl(),
                LocalDateTime.ofInstant(summary.startedAt(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(summary.completedAt(), ZoneOffset.UTC), summary.complete(),
                summary.filtered(), summary.checksum(), summary.recordCount(), summary.pageCount(), payloadJson);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
