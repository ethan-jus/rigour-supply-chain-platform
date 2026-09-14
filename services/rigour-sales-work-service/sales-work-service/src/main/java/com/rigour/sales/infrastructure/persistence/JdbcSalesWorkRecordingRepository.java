package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesWorkRecordingRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 拜访录音元数据 JDBC 仓储；只写 Sales Work 自有 Schema。 */
@Repository
public class JdbcSalesWorkRecordingRepository implements SalesWorkRecordingRepository {

    private static final long OVERLAP_REVIEW_TOLERANCE_MS = 1_000L;

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
                       object_size_bytes, sha256, perceptual_hash, client_duration_ms, verified_duration_ms,
                       upload_status, verify_status, recorded_from, recorded_to, created_at
                  FROM sales_recording_clip
                 WHERE tenant_id=? AND recording_session_id=? AND client_clip_id=?
                 LIMIT 1
                """, (rs, row) -> clip(rs), bin(tenantId), bin(sessionId), clientClipId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<RecordingClipRow> findClipBySha256(UUID tenantId, UUID sessionId, String sha256) {
        List<RecordingClipRow> rows = jdbc.query("""
                SELECT id, recording_session_id, client_clip_id, clip_index, object_key, media_type,
                       object_size_bytes, sha256, perceptual_hash, client_duration_ms, verified_duration_ms,
                       upload_status, verify_status, recorded_from, recorded_to, created_at
                  FROM sales_recording_clip
                 WHERE tenant_id=? AND recording_session_id=? AND sha256=?
                 ORDER BY clip_index ASC
                 LIMIT 1
                """, (rs, row) -> clip(rs), bin(tenantId), bin(sessionId), sha256);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<RecordingClipRow> findClipByDecodedContentHash(
            UUID tenantId, UUID sessionId, String decodedContentHash) {
        List<RecordingClipRow> rows = jdbc.query("""
                SELECT id, recording_session_id, client_clip_id, clip_index, object_key, media_type,
                       object_size_bytes, sha256, perceptual_hash, client_duration_ms, verified_duration_ms,
                       upload_status, verify_status, recorded_from, recorded_to, created_at
                  FROM sales_recording_clip
                 WHERE tenant_id=? AND recording_session_id=? AND perceptual_hash=?
                 ORDER BY clip_index ASC
                 LIMIT 1
                """, (rs, row) -> clip(rs), bin(tenantId), bin(sessionId), decodedContentHash);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<RecordingClipRow> findClipByDecodedContentHashOutsideSession(
            UUID tenantId, UUID sessionId, String decodedContentHash) {
        List<RecordingClipRow> rows = jdbc.query("""
                SELECT id, recording_session_id, client_clip_id, clip_index, object_key, media_type,
                       object_size_bytes, sha256, perceptual_hash, client_duration_ms, verified_duration_ms,
                       upload_status, verify_status, recorded_from, recorded_to, created_at
                  FROM sales_recording_clip
                 WHERE tenant_id=? AND recording_session_id<>? AND perceptual_hash=?
                 ORDER BY created_at ASC
                 LIMIT 1
                """, (rs, row) -> clip(rs), bin(tenantId), bin(sessionId), decodedContentHash);
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
                           String mediaType, long objectSizeBytes, String sha256,
                           String decodedContentHash, Long clientDurationMs,
                           Long verifiedDurationMs, String verifyStatus,
                           Instant recordedFrom, Instant recordedTo, Instant now) {
        jdbc.update("""
                INSERT INTO sales_recording_clip
                    (id, tenant_id, recording_session_id, client_clip_id, clip_index, object_key, media_type,
                     object_size_bytes, sha256, perceptual_hash, client_duration_ms,
                     verified_duration_ms, recorded_from, recorded_to, upload_status,
                     verify_status, created_at, verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?)
                """, bin(id), bin(tenantId), bin(sessionId), clientClipId, clipIndex, objectKey, mediaType,
                objectSizeBytes, sha256, decodedContentHash, clientDurationMs, verifiedDurationMs,
                timestamp(recordedFrom),
                timestamp(recordedTo), verifyStatus, timestamp(now),
                "VERIFIED".equals(verifyStatus) ? timestamp(now) : null);
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
    public int refreshSessionVerification(UUID tenantId, UUID sessionId,
                                          int maximumClipGapSeconds, Instant verifiedAt) {
        String currentEvidenceStatus = jdbc.queryForObject("""
                SELECT evidence_status FROM sales_recording_session
                 WHERE tenant_id=? AND id=?
                """, String.class, bin(tenantId), bin(sessionId));
        EvidenceSummary summary = summarize(findClips(tenantId, sessionId), maximumClipGapSeconds,
                "REVIEW_REQUIRED".equals(currentEvidenceStatus));
        return jdbc.update("""
                UPDATE sales_recording_session
                   SET verified_total_duration_ms=?, maximum_observed_gap_ms=?, evidence_status=?,
                       version=version+1, updated_at=?
                 WHERE tenant_id=? AND id=?
                """, summary.verifiedTotalDurationMs(), summary.maximumObservedGapMs(),
                summary.evidenceStatus(), timestamp(verifiedAt), bin(tenantId), bin(sessionId));
    }

    @Override
    public void flagSessionForReview(UUID tenantId, UUID sessionId, Instant detectedAt) {
        jdbc.update("""
                UPDATE sales_recording_session
                   SET evidence_status='REVIEW_REQUIRED', version=version+1, updated_at=?
                 WHERE tenant_id=? AND id=? AND evidence_status<>'REVIEW_REQUIRED'
                """, timestamp(detectedAt), bin(tenantId), bin(sessionId));
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
                       object_size_bytes, sha256, perceptual_hash, client_duration_ms, verified_duration_ms,
                       upload_status, verify_status, recorded_from, recorded_to, created_at
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
                rs.getString("perceptual_hash"),
                rs.getObject("client_duration_ms", Long.class),
                rs.getObject("verified_duration_ms", Long.class),
                rs.getString("upload_status"), rs.getString("verify_status"),
                timestamp(rs, "recorded_from"), timestamp(rs, "recorded_to"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** 仅聚合服务端已核验区间；客户端时间只定义位置，不能把重叠片段重复计时。 */
    static EvidenceSummary summarize(List<RecordingClipRow> clips, int maximumClipGapSeconds,
                                     boolean reviewAlreadyRequired) {
        boolean reviewRequired = reviewAlreadyRequired;
        boolean hasUnverified = false;
        Set<String> contentIdentities = new HashSet<>();
        List<Interval> claimedIntervals = new ArrayList<>();
        List<Interval> verifiedIntervals = new ArrayList<>();
        for (RecordingClipRow clip : clips) {
            if (!"RECEIVED".equals(clip.uploadStatus())) {
                hasUnverified = true;
                continue;
            }
            if (clip.sha256() == null || clip.sha256().isBlank()) {
                reviewRequired = true;
                continue;
            }
            String contentIdentity = clip.decodedContentHash() == null
                    || clip.decodedContentHash().isBlank()
                    ? "file:" + clip.sha256()
                    : "decoded:" + clip.decodedContentHash();
            if (!contentIdentities.add(contentIdentity)) {
                reviewRequired = true;
                continue;
            }
            if (!validInterval(clip.recordedFrom(), clip.recordedTo())) {
                reviewRequired = true;
                hasUnverified = true;
                continue;
            }
            claimedIntervals.add(new Interval(clip.recordedFrom(), clip.recordedTo()));
            if (!"VERIFIED".equals(clip.verifyStatus())) {
                hasUnverified = true;
                continue;
            }
            if (clip.verifiedDurationMs() == null || clip.verifiedDurationMs() <= 0) {
                reviewRequired = true;
                hasUnverified = true;
                continue;
            }
            Instant decodedEnd;
            try {
                decodedEnd = clip.recordedFrom().plusMillis(clip.verifiedDurationMs());
            } catch (RuntimeException error) {
                reviewRequired = true;
                hasUnverified = true;
                continue;
            }
            if (!decodedEnd.isAfter(clip.recordedFrom())) {
                reviewRequired = true;
                hasUnverified = true;
                continue;
            }
            verifiedIntervals.add(new Interval(clip.recordedFrom(), decodedEnd));
        }

        IntervalScan claimed = scan(claimedIntervals);
        IntervalScan verified = scan(verifiedIntervals);
        reviewRequired |= claimed.overlapDetected() || verified.overlapDetected();
        long maximumAllowedGapMs = Math.max(0L, (long) maximumClipGapSeconds * 1_000L);
        if (verified.maximumGapMs() > maximumAllowedGapMs) reviewRequired = true;
        long verifiedTotalDurationMs = unionDuration(verifiedIntervals);
        String evidenceStatus;
        if (reviewRequired) {
            evidenceStatus = "REVIEW_REQUIRED";
        } else if (hasUnverified) {
            evidenceStatus = "PENDING";
        } else if (!verifiedIntervals.isEmpty()) {
            evidenceStatus = "TECHNICALLY_VERIFIED";
        } else {
            evidenceStatus = "PENDING";
        }
        return new EvidenceSummary(verifiedTotalDurationMs, verified.maximumGapMs(), evidenceStatus);
    }

    private static boolean validInterval(Instant from, Instant to) {
        return from != null && to != null && from.isBefore(to);
    }

    private static IntervalScan scan(List<Interval> intervals) {
        if (intervals.isEmpty()) return new IntervalScan(0L, false);
        intervals.sort(Comparator.comparing(Interval::from).thenComparing(Interval::to));
        Instant currentEnd = intervals.getFirst().to();
        long maximumGapMs = 0L;
        boolean overlapDetected = false;
        for (int index = 1; index < intervals.size(); index++) {
            Interval next = intervals.get(index);
            if (next.from().isBefore(currentEnd)) {
                Instant overlapEnd = next.to().isBefore(currentEnd) ? next.to() : currentEnd;
                long overlapMs = Duration.between(next.from(), overlapEnd).toMillis();
                if (overlapMs > OVERLAP_REVIEW_TOLERANCE_MS) overlapDetected = true;
                if (next.to().isAfter(currentEnd)) currentEnd = next.to();
                continue;
            }
            maximumGapMs = Math.max(maximumGapMs, Duration.between(currentEnd, next.from()).toMillis());
            currentEnd = next.to();
        }
        return new IntervalScan(maximumGapMs, overlapDetected);
    }

    private static long unionDuration(List<Interval> intervals) {
        if (intervals.isEmpty()) return 0L;
        intervals.sort(Comparator.comparing(Interval::from).thenComparing(Interval::to));
        Instant currentFrom = intervals.getFirst().from();
        Instant currentTo = intervals.getFirst().to();
        long total = 0L;
        for (int index = 1; index < intervals.size(); index++) {
            Interval next = intervals.get(index);
            if (!next.from().isAfter(currentTo)) {
                if (next.to().isAfter(currentTo)) currentTo = next.to();
                continue;
            }
            total = Math.addExact(total, Duration.between(currentFrom, currentTo).toMillis());
            currentFrom = next.from();
            currentTo = next.to();
        }
        return Math.addExact(total, Duration.between(currentFrom, currentTo).toMillis());
    }

    private static Instant timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record EvidenceSummary(long verifiedTotalDurationMs, long maximumObservedGapMs,
                           String evidenceStatus) {
    }

    private record Interval(Instant from, Instant to) {
    }

    private record IntervalScan(long maximumGapMs, boolean overlapDetected) {
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }
}
