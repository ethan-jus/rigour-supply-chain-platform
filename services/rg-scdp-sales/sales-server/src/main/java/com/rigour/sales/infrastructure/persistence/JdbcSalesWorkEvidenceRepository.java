package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesWorkEvidenceRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 拜访照片证据 JDBC 仓储；只写 Sales Work 自有证据表。 */
@Repository
public class JdbcSalesWorkEvidenceRepository implements SalesWorkEvidenceRepository {

    private final JdbcTemplate jdbc;

    public JdbcSalesWorkEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PhotoEvidenceRow> findPhotoByClientEvidenceId(
            UUID tenantId, UUID visitId, String clientEvidenceId) {
        return jdbc.query("""
                SELECT id, visit_id, client_evidence_id, evidence_role, capture_source, captured_at,
                       object_key, media_type, object_size_bytes, content_hash,
                       longitude, latitude, accuracy_meters, distance_to_target_meters,
                       evidence_status, server_received_at
                  FROM sales_visit_evidence
                 WHERE tenant_id=? AND visit_id=? AND client_evidence_id=?
                   AND evidence_type='PHOTO'
                 LIMIT 1
                """, (rs, rowNum) -> photo(rs), bin(tenantId), bin(visitId), clientEvidenceId)
                .stream().findFirst();
    }

    @Override
    public void insertStorefrontPhoto(
            UUID id, UUID tenantId, UUID visitId, String clientEvidenceId,
            String objectKey, String mediaType, long objectSizeBytes, String contentHash,
            String captureSource, Instant capturedAt,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            BigDecimal distanceToTargetMeters, UUID createdBy, Instant receivedAt) {
        jdbc.update("""
                INSERT INTO sales_visit_evidence
                    (id, tenant_id, visit_id, client_evidence_id, evidence_type, evidence_role,
                     capture_source, captured_at, object_key, media_type, object_size_bytes,
                     text_content, content_hash, longitude, latitude, accuracy_meters,
                     distance_to_target_meters, server_received_at, evidence_status,
                     created_by, created_at)
                VALUES (?, ?, ?, ?, 'PHOTO', 'STOREFRONT', ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?,
                        'TECHNICALLY_VERIFIED', ?, ?)
                """, bin(id), bin(tenantId), bin(visitId), clientEvidenceId,
                captureSource, timestamp(capturedAt), objectKey, mediaType, objectSizeBytes,
                contentHash, longitude, latitude, accuracyMeters, distanceToTargetMeters,
                timestamp(receivedAt), bin(createdBy), timestamp(receivedAt));
    }

    @Override
    public List<PhotoEvidenceRow> findStorefrontPhotos(UUID tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT id, visit_id, client_evidence_id, evidence_role, capture_source, captured_at,
                       object_key, media_type, object_size_bytes, content_hash,
                       longitude, latitude, accuracy_meters, distance_to_target_meters,
                       evidence_status, server_received_at
                  FROM sales_visit_evidence
                 WHERE tenant_id=? AND visit_id=? AND evidence_type='PHOTO'
                   AND evidence_role='STOREFRONT'
                 ORDER BY captured_at ASC, server_received_at ASC
                """, (rs, rowNum) -> photo(rs), bin(tenantId), bin(visitId));
    }

    private static PhotoEvidenceRow photo(ResultSet rs) throws SQLException {
        return new PhotoEvidenceRow(
                SalesUuidCodec.decode(rs.getBytes("id")),
                SalesUuidCodec.decode(rs.getBytes("visit_id")),
                rs.getString("client_evidence_id"), rs.getString("evidence_role"),
                rs.getString("capture_source"), instant(rs.getTimestamp("captured_at")),
                rs.getString("object_key"), rs.getString("media_type"),
                rs.getLong("object_size_bytes"), rs.getString("content_hash"),
                rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("accuracy_meters"), rs.getBigDecimal("distance_to_target_meters"),
                rs.getString("evidence_status"), instant(rs.getTimestamp("server_received_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }
}
