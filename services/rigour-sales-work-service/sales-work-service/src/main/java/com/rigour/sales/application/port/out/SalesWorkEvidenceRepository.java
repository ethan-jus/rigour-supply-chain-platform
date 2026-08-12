package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 拜访照片证据元数据端口；图片字节由 FileStorage 保管。 */
public interface SalesWorkEvidenceRepository {

    Optional<PhotoEvidenceRow> findPhotoByClientEvidenceId(
            UUID tenantId, UUID visitId, String clientEvidenceId);

    void insertStorefrontPhoto(
            UUID id, UUID tenantId, UUID visitId, String clientEvidenceId,
            String objectKey, String mediaType, long objectSizeBytes, String contentHash,
            String captureSource, Instant capturedAt,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            BigDecimal distanceToTargetMeters, UUID createdBy, Instant receivedAt);

    List<PhotoEvidenceRow> findStorefrontPhotos(UUID tenantId, UUID visitId);

    record PhotoEvidenceRow(
            UUID id, UUID visitId, String clientEvidenceId, String evidenceRole,
            String captureSource, Instant capturedAt, String objectKey, String mediaType,
            long objectSizeBytes, String contentHash,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            BigDecimal distanceToTargetMeters, String evidenceStatus, Instant serverReceivedAt) {
    }
}
