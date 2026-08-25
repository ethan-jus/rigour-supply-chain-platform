package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 物理删除任务仓储；对象删除由服务层在数据库事务外执行。 */
@Repository
public class TemporaryCheckinDeletionRepository {

    private final JdbcTemplate jdbc;

    TemporaryCheckinDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int insertJob(
            UUID id, UUID tenantId, UUID requestId, String requestedBy, String scopeCity,
            String reason, int requestedCount, String resultJson, Instant now) {
        return jdbc.update("""
                INSERT INTO temp_sales_checkin_deletion_job (
                    id, tenant_id, request_id, requested_by, requested_scope_city, reason,
                    requested_count, deleted_count, failed_count, status, result_json,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'PENDING', ?, ?, ?, NULL)
                """, bin(id), bin(tenantId), bin(requestId), requestedBy, scopeCity, reason,
                requestedCount, resultJson, timestamp(now), timestamp(now));
    }

    Optional<DeletionJobRow> findJob(UUID tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT request_id, status, requested_count, deleted_count, failed_count,
                       result_json, completed_at
                  FROM temp_sales_checkin_deletion_job
                 WHERE tenant_id=? AND request_id=?
                 LIMIT 1
                """, (rs, row) -> deletionJob(rs), bin(tenantId), bin(requestId)).stream().findFirst();
    }

    int markJobProcessing(UUID tenantId, UUID requestId, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_deletion_job
                   SET status='PROCESSING', updated_at=?
                 WHERE tenant_id=? AND request_id=? AND status IN ('PENDING','FAILED','PARTIAL_FAILED')
                """, timestamp(now), bin(tenantId), bin(requestId));
    }

    int finishJob(
            UUID tenantId, UUID requestId, String status, int deletedCount, int failedCount,
            String resultJson, Instant completedAt) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_deletion_job
                   SET status=?, deleted_count=?, failed_count=?, result_json=?,
                       updated_at=?, completed_at=?
                 WHERE tenant_id=? AND request_id=?
                """, status, deletedCount, failedCount, resultJson, timestamp(completedAt),
                timestamp(completedAt), bin(tenantId), bin(requestId));
    }

    List<DeletionCandidateRow> findCandidates(
            UUID tenantId, List<UUID> ids, String scopeCity) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT id, city, deletion_state,
                       storefront_photo_object_key, storefront_photo_deleted_at,
                       wechat_screenshot_object_key, wechat_screenshot_deleted_at,
                       audio_object_key, audio_deleted_at, audio_segments_json
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id IN (""" + placeholders + ")"
                + (scopeCity == null ? "" : " AND city=?")
                + " ORDER BY id FOR UPDATE";
        List<Object> arguments = new ArrayList<>();
        arguments.add(bin(tenantId));
        ids.forEach(id -> arguments.add(bin(id)));
        if (scopeCity != null) arguments.add(scopeCity);
        return jdbc.query(sql, (rs, row) -> candidate(rs), arguments.toArray());
    }

    int markPending(UUID tenantId, UUID id, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET deletion_state='PENDING', updated_at=?
                 WHERE tenant_id=? AND id=? AND deletion_state IN ('NONE','FAILED')
                """, timestamp(now), bin(tenantId), bin(id));
    }

    int markFailed(UUID tenantId, UUID id, Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET deletion_state='FAILED', updated_at=?
                 WHERE tenant_id=? AND id=? AND deletion_state='PENDING'
                """, timestamp(now), bin(tenantId), bin(id));
    }

    int hardDelete(UUID tenantId, UUID id) {
        return jdbc.update("""
                DELETE FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=? AND deletion_state='PENDING'
                """, bin(tenantId), bin(id));
    }

    private static DeletionCandidateRow candidate(ResultSet rs) throws SQLException {
        return new DeletionCandidateRow(
                uuid(rs, "id"), rs.getString("city"), rs.getString("deletion_state"),
                activeKey(rs, "storefront_photo_"), activeKey(rs, "wechat_screenshot_"),
                activeKey(rs, "audio_"), rs.getString("audio_segments_json"));
    }

    private static String activeKey(ResultSet rs, String prefix) throws SQLException {
        return rs.getTimestamp(prefix + "deleted_at") == null ? rs.getString(prefix + "object_key") : null;
    }

    private static DeletionJobRow deletionJob(ResultSet rs) throws SQLException {
        return new DeletionJobRow(
                uuid(rs, "request_id"), rs.getString("status"), rs.getInt("requested_count"),
                rs.getInt("deleted_count"), rs.getInt("failed_count"), rs.getString("result_json"),
                instant(rs, "completed_at"));
    }

    private static byte[] bin(UUID value) { return SalesUuidCodec.encode(value); }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return SalesUuidCodec.decode(rs.getBytes(column));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record DeletionCandidateRow(
            UUID id, String city, String deletionState,
            String storefrontPhotoKey, String wechatScreenshotKey, String audioKey,
            String audioSegmentsJson) {
        DeletionCandidateRow(
                UUID id, String city, String deletionState,
                String storefrontPhotoKey, String wechatScreenshotKey, String audioKey) {
            this(id, city, deletionState, storefrontPhotoKey, wechatScreenshotKey, audioKey, "[]");
        }

        List<String> projectedObjectKeys() {
            return java.util.stream.Stream.of(storefrontPhotoKey, wechatScreenshotKey, audioKey)
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        }
    }

    record DeletionJobRow(
            UUID requestId, String status, int requestedCount, int deletedCount,
            int failedCount, String resultJson, Instant completedAt) { }
}
