package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesWorkRecordingRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 拜访录音元数据 JDBC 仓储；只写 Sales Work 自有 Schema。 */
@Repository
public class JdbcSalesWorkRecordingRepository implements SalesWorkRecordingRepository {

    private final JdbcTemplate jdbc;

    public JdbcSalesWorkRecordingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RecordingSessionRow> findSession(UUID tenantId, UUID visitId) {
        List<RecordingSessionRow> rows = jdbc.query("""
                SELECT id, visit_id, status, evidence_status, clip_count, verified_total_duration_ms
                  FROM sales_recording_session
                 WHERE tenant_id=? AND visit_id=?
                 LIMIT 1
                """, (rs, row) -> new RecordingSessionRow(
                        SalesUuidCodec.decode(rs.getBytes("id")), SalesUuidCodec.decode(rs.getBytes("visit_id")),
                        rs.getString("status"), rs.getString("evidence_status"), rs.getInt("clip_count"),
                        rs.getLong("verified_total_duration_ms")),
                bin(tenantId), bin(visitId));
        return rows.stream().findFirst();
    }

    @Override
    public UUID ensureSession(UUID id, UUID tenantId, UUID visitId, Instant now) {
        jdbc.update("""
                INSERT INTO sales_recording_session
                    (id, tenant_id, visit_id, status, verified_total_duration_ms, clip_count,
                     maximum_observed_gap_ms, evidence_status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, 0, 0, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id=id
                """, bin(id), bin(tenantId), bin(visitId));
        return findSession(tenantId, visitId)
                .orElseThrow(() -> new IllegalStateException("录音会话创建后不可见"))
                .id();
    }

    @Override
    public void lockSession(UUID tenantId, UUID sessionId) {
        jdbc.queryForObject("""
                SELECT id FROM sales_recording_session
                 WHERE tenant_id=? AND id=?
                 FOR UPDATE
                """, (rs, row) -> SalesUuidCodec.decode(rs.getBytes("id")),
                bin(tenantId), bin(sessionId));
    }

    @Override
    public Optional<RecordingClipRow> findClipByClientId(
            UUID tenantId, UUID sessionId, String clientClipId) {
        List<RecordingClipRow> rows = jdbc.query("""
                SELECT id, recording_session_id, client_clip_id, clip_index, object_key, media_type,
                       object_size_bytes, sha256, client_duration_ms, verified_duration_ms,
                       upload_status, verify_status, created_at
                  FROM sales_recording_clip
                 WHERE tenant_id=? AND recording_session_id=? AND client_clip_id=?
                 LIMIT 1
                """, (rs, row) -> clip(rs), bin(tenantId), bin(sessionId), clientClipId);
        return rows.stream().findFirst();
    }

    @Override
    public int nextClipIndex(UUID tenantId, UUID sessionId) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(clip_index), -1) FROM sales_recording_clip
                 WHERE tenant_id=? AND recording_session_id=?
                """, Integer.class, bin(tenantId), bin(sessionId));
        return (current == null ? -1 : current) + 1;
    }

    @Override
    public void insertClip(UUID id, UUID tenantId, UUID sessionId, String clientClipId,
                           int clipIndex, String objectKey,
                           String mediaType, long objectSizeBytes, String sha256, Long clientDurationMs,
                           Long verifiedDurationMs, String verifyStatus,
                           Instant recordedFrom, Instant recordedTo, Instant now) {
        jdbc.update("""
                INSERT INTO sales_recording_clip
                    (id, tenant_id, recording_session_id, client_clip_id, clip_index, object_key, media_type,
                     object_size_bytes, sha256, perceptual_hash, client_duration_ms,
                     verified_duration_ms, recorded_from, recorded_to, upload_status,
                     verify_status, created_at, verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?)
                """, bin(id), bin(tenantId), bin(sessionId), clientClipId, clipIndex, objectKey, mediaType,
                objectSizeBytes, sha256, clientDurationMs, verifiedDurationMs, timestamp(recordedFrom),
                timestamp(recordedTo), verifyStatus, timestamp(now),
                "PENDING".equals(verifyStatus) ? null : timestamp(now));
    }

    @Override
    public int incrementSessionClipCount(UUID tenantId, UUID sessionId) {
        return jdbc.update("""
                UPDATE sales_recording_session
                   SET clip_count=clip_count+1,
                       version=version+1, updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND id=?
                """, bin(tenantId), bin(sessionId));
    }

    @Override
    public int refreshSessionVerification(UUID tenantId, UUID sessionId, Instant verifiedAt) {
        return jdbc.update("""
                UPDATE sales_recording_session session
                   SET verified_total_duration_ms=(
                           SELECT COALESCE(SUM(clip.verified_duration_ms), 0)
                             FROM sales_recording_clip clip
                            WHERE clip.tenant_id=session.tenant_id
                              AND clip.recording_session_id=session.id
                              AND clip.verify_status='VERIFIED'),
                       evidence_status=CASE
                           WHEN EXISTS (SELECT 1 FROM sales_recording_clip clip
                                         WHERE clip.tenant_id=session.tenant_id
                                           AND clip.recording_session_id=session.id
                                           AND clip.verify_status<>'VERIFIED') THEN 'PENDING'
                           WHEN EXISTS (SELECT 1 FROM sales_recording_clip clip
                                         WHERE clip.tenant_id=session.tenant_id
                                           AND clip.recording_session_id=session.id
                                           AND clip.verify_status='VERIFIED') THEN 'TECHNICALLY_VERIFIED'
                           ELSE 'PENDING' END,
                       version=version+1, updated_at=?
                 WHERE session.tenant_id=? AND session.id=?
                """, timestamp(verifiedAt), bin(tenantId), bin(sessionId));
    }

    @Override
    public long uploadedTotalDurationMs(UUID tenantId, UUID sessionId) {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(client_duration_ms), 0)
                  FROM sales_recording_clip
                 WHERE tenant_id=? AND recording_session_id=? AND upload_status='RECEIVED'
                """, Long.class, bin(tenantId), bin(sessionId));
        return total == null ? 0L : total;
    }

    @Override
    public List<RecordingClipRow> findClips(UUID tenantId, UUID sessionId) {
        return jdbc.query("""
                SELECT id, recording_session_id, client_clip_id, clip_index, object_key, media_type,
                       object_size_bytes, sha256, client_duration_ms, verified_duration_ms,
                       upload_status, verify_status, created_at
                  FROM sales_recording_clip
                 WHERE tenant_id=? AND recording_session_id=?
                 ORDER BY clip_index ASC
                """, (rs, row) -> clip(rs),
                bin(tenantId), bin(sessionId));
    }

    @Override
    public Optional<RecordingDiscardRow> findDiscardByClientId(
            UUID tenantId, UUID visitId, String clientClipId) {
        List<RecordingDiscardRow> rows = jdbc.query("""
                SELECT id, visit_id, client_clip_id, client_duration_ms, recorded_from, recorded_to,
                       discard_reason, disposition, created_at
                  FROM sales_recording_discard
                 WHERE tenant_id=? AND visit_id=? AND client_clip_id=?
                 LIMIT 1
                """, (rs, row) -> new RecordingDiscardRow(
                        SalesUuidCodec.decode(rs.getBytes("id")),
                        SalesUuidCodec.decode(rs.getBytes("visit_id")),
                        rs.getString("client_clip_id"), rs.getLong("client_duration_ms"),
                        rs.getTimestamp("recorded_from").toInstant(),
                        rs.getTimestamp("recorded_to").toInstant(),
                        rs.getString("discard_reason"), rs.getString("disposition"),
                        rs.getTimestamp("created_at").toInstant()),
                bin(tenantId), bin(visitId), clientClipId);
        return rows.stream().findFirst();
    }

    @Override
    public void insertDiscard(UUID id, UUID tenantId, UUID visitId, String clientClipId,
                              long clientDurationMs, Instant recordedFrom, Instant recordedTo,
                              String reason, Instant now) {
        jdbc.update("""
                INSERT INTO sales_recording_discard
                    (id, tenant_id, visit_id, client_clip_id, client_duration_ms,
                     recorded_from, recorded_to, discard_reason, disposition, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DISCARDED_NOT_STORED', ?)
                """, bin(id), bin(tenantId), bin(visitId), clientClipId, clientDurationMs,
                timestamp(recordedFrom), timestamp(recordedTo), reason, timestamp(now));
    }

    private static RecordingClipRow clip(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RecordingClipRow(
                SalesUuidCodec.decode(rs.getBytes("id")),
                SalesUuidCodec.decode(rs.getBytes("recording_session_id")),
                rs.getString("client_clip_id"), rs.getInt("clip_index"),
                rs.getString("object_key"), rs.getString("media_type"),
                rs.getLong("object_size_bytes"), rs.getString("sha256"),
                rs.getObject("client_duration_ms", Long.class),
                rs.getObject("verified_duration_ms", Long.class),
                rs.getString("upload_status"), rs.getString("verify_status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }
}
