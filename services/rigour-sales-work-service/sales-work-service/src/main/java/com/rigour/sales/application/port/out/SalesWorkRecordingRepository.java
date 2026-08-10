package com.rigour.sales.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 拜访录音会话与片段元数据持久化端口；音频字节由 FileStorage 保管，这里只登记事实。 */
public interface SalesWorkRecordingRepository {

    Optional<RecordingSessionRow> findSession(UUID tenantId, UUID visitId);

    UUID ensureSession(UUID id, UUID tenantId, UUID visitId, Instant now);

    void lockSession(UUID tenantId, UUID sessionId);

    Optional<RecordingClipRow> findClipByClientId(UUID tenantId, UUID sessionId, String clientClipId);

    int nextClipIndex(UUID tenantId, UUID sessionId);

    void insertClip(UUID id, UUID tenantId, UUID sessionId, String clientClipId,
                    int clipIndex, String objectKey,
                    String mediaType, long objectSizeBytes, String sha256, Long clientDurationMs,
                    Instant recordedFrom, Instant recordedTo, Instant now);

    int incrementSessionClipCount(UUID tenantId, UUID sessionId);

    long uploadedTotalDurationMs(UUID tenantId, UUID sessionId);

    List<RecordingClipRow> findClips(UUID tenantId, UUID sessionId);

    Optional<RecordingDiscardRow> findDiscardByClientId(
            UUID tenantId, UUID visitId, String clientClipId);

    void insertDiscard(UUID id, UUID tenantId, UUID visitId, String clientClipId,
                       long clientDurationMs, Instant recordedFrom, Instant recordedTo,
                       String reason, Instant now);

    record RecordingSessionRow(
            UUID id, UUID visitId, String status, int clipCount, long verifiedTotalDurationMs) {
    }

    record RecordingClipRow(
            UUID id, UUID sessionId, String clientClipId, int clipIndex, String objectKey, String mediaType,
            long objectSizeBytes, String sha256, Long clientDurationMs,
            String uploadStatus, Instant createdAt) {
    }

    record RecordingDiscardRow(
            UUID id, UUID visitId, String clientClipId, long clientDurationMs,
            Instant recordedFrom, Instant recordedTo, String reason,
            String disposition, Instant createdAt) {
    }
}
