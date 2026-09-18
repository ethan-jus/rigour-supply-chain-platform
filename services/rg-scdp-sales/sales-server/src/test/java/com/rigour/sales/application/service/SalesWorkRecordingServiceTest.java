package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipCommand;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
import com.rigour.sales.application.port.out.RecordingMediaVerifier;
import com.rigour.sales.application.port.out.RecordingMediaVerifier.Verification;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.IdentityProjection;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.SalesProfile;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.VisitPolicy;
import com.rigour.sales.application.port.out.SalesWorkRecordingRepository;
import com.rigour.sales.application.port.out.SalesWorkRecordingRepository.RecordingClipRow;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository.VisitSnapshot;
import com.rigour.sales.infrastructure.config.SalesRecordingProperties;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.file.FileStorage;
import com.rigour.shared.file.FileMetadata;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/** 不依赖容器验证短录音最小化存储和服务端防绕过。 */
class SalesWorkRecordingServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-09T03:00:00Z");
    private SalesWorkRecordingService service;
    private SalesWorkRecordingRepository recordingRepository;
    private SalesWorkVisitRepository visitRepository;
    private RecordingMediaVerifier mediaVerifier;
    private FileStorage fileStorage;
    private AuditSink auditSink;

    @BeforeEach
    void setUp() throws Exception {
        recordingRepository = mock(SalesWorkRecordingRepository.class);
        visitRepository = mock(SalesWorkVisitRepository.class);
        SalesWorkQueryRepository queryRepository = mock(SalesWorkQueryRepository.class);
        SalesWorkContextService contextService = mock(SalesWorkContextService.class);
        mediaVerifier = mock(RecordingMediaVerifier.class);
        SalesWorkVisitAssessmentService assessmentService = mock(SalesWorkVisitAssessmentService.class);
        fileStorage = mock(FileStorage.class);
        auditSink = mock(AuditSink.class);
        SalesRecordingProperties properties = new SalesRecordingProperties();
        properties.setMinimumClipSeconds(30);

        CallerIdentity caller = new CallerIdentity("TENANT", userId, tenantId, userId, null,
                UUID.randomUUID(), 1, 1, 1, Set.of("SALES"),
                Set.of("sales:recording:own:write"));
        Method set = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        set.setAccessible(true);
        set.invoke(null, caller);
        RequestContext.set("recording-unit-test", "zh-CN");

        var identity = new SalesWorkContextService.SalesIdentity(
                new IdentityProjection(userId, UUID.randomUUID(), "ACTIVE"),
                new SalesProfile(profileId, UUID.randomUUID(), "S-001", null, "ACTIVE"));
        when(contextService.resolveIdentity(any(), any())).thenReturn(identity);
        when(recordingRepository.findDiscardByClientId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(recordingRepository.ensureSession(any(), any(), any(), any())).thenReturn(sessionId);
        when(queryRepository.findVisitPolicy(any(), any())).thenReturn(Optional.of(new VisitPolicy(
                UUID.randomUUID(), "VISIT-TEST", "测试拜访规则", 1, "PUBLISHED",
                true, true, 500, 0, 1, true, 30, 30)));
        when(visitRepository.findVisit(tenantId, profileId, visitId)).thenReturn(Optional.of(
                new VisitSnapshot(visitId, UUID.randomUUID(), profileId, "MY_STORE",
                        UUID.randomUUID(), UUID.randomUUID(), "CHECKED_IN",
                        now.minusSeconds(120), null, UUID.randomUUID(), now.minusSeconds(120),
                        null, null, null, null, null, null, null, null, null)));

        service = new SalesWorkRecordingService(recordingRepository, visitRepository, queryRepository,
                contextService, mediaVerifier, assessmentService, fileStorage, properties, auditSink,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        Method clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
        RequestContext.clear();
    }

    @Test
    void uploadBelowThirtySecondsIsRejectedBeforeObjectStorage() {
        Instant recordedTo = now.minusSeconds(10);
        var file = new MockMultipartFile("file", "short.aac", "audio/aac", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.uploadClip(visitId, file, "short-upload", 20_000L,
                recordedTo.minusSeconds(20), recordedTo))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_RECORDING_INVALID);
                    assertThat(error.getMessage()).contains("不足 30 秒");
                });

        verify(fileStorage, never()).put(any(), any());
        verify(auditSink, never()).append(any(AuditEvent.class));
    }

    @Test
    void discardedShortClipOnlyWritesAuditMetadata() {
        Instant recordedTo = now.minusSeconds(10);
        var result = service.discardClip(visitId, new DiscardRecordingClipCommand(
                "short-discard", 20_000L, recordedTo.minusSeconds(20), recordedTo, "TOO_SHORT"));

        assertThat(result.disposition()).isEqualTo("DISCARDED_NOT_STORED");
        verify(fileStorage, never()).put(any(), any());
        verify(recordingRepository).insertDiscard(any(), any(), any(), any(),
                anyLong(), any(), any(), any(), any());
        verify(auditSink).append(any(AuditEvent.class));
    }

    @Test
    void invalidAacKeepsSafeVerifierReasonWithoutWritingStorage() {
        Instant recordedTo = now.minusSeconds(10);
        byte[] bytes = new byte[] {1, 2, 3, 4, 5, 6, 7};
        var file = new MockMultipartFile("file", "clip.aac", "audio/aac", bytes);
        when(mediaVerifier.verify("audio/aac", bytes))
                .thenReturn(Verification.invalid("AAC_CONTAINER_UNSUPPORTED"));

        assertThatThrownBy(() -> service.uploadClip(visitId, file, "invalid-aac", 35_000L,
                recordedTo.minusSeconds(35), recordedTo))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_RECORDING_INVALID);
                    assertThat(error.getDetails()).singleElement().satisfies(detail -> {
                        assertThat(detail.field()).isEqualTo("file");
                        assertThat(detail.reason()).isEqualTo("AAC_CONTAINER_UNSUPPORTED");
                    });
                });

        verify(fileStorage, never()).put(any(), any());
    }

    @Test
    void structurallyValidM4aIsStoredPendingWithCanonicalMediaTypeAndNoTrustedDuration() {
        Instant recordedTo = now.minusSeconds(10);
        byte[] bytes = new byte[] {0, 0, 0, 20, 'f', 't', 'y', 'p'};
        var file = new MockMultipartFile("file", "local-1.aac", "audio/aac", bytes);
        when(mediaVerifier.verify("audio/aac", bytes))
                .thenReturn(Verification.structurallyValid(35_000L, "audio/m4a"));

        service.uploadClip(visitId, file, "local-1", 35_000L,
                recordedTo.minusSeconds(35), recordedTo);

        ArgumentCaptor<FileMetadata> metadata = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileStorage).put(metadata.capture(), any());
        assertThat(metadata.getValue().contentType()).isEqualTo("audio/m4a");
        assertThat(metadata.getValue().objectKey()).endsWith(".m4a");
        assertThat(metadata.getValue().originalName()).isEqualTo("local-1.m4a");
        verify(recordingRepository).insertClip(any(), eq(tenantId), eq(sessionId), eq("local-1"),
                eq(0), any(), eq("audio/m4a"), eq((long) bytes.length), any(), isNull(),
                eq(35_000L), isNull(), eq("PENDING"), any(), any(), any());
    }

    @Test
    void sameAudioWithDifferentClientIdDoesNotStoreOrAccumulateAgain() {
        Instant recordedTo = now.minusSeconds(10);
        Instant recordedFrom = recordedTo.minusSeconds(35);
        byte[] bytes = new byte[] {0x11, 0x22, 0x33};
        String sha256 = sha256(bytes);
        var existing = new RecordingClipRow(UUID.randomUUID(), sessionId, "first-client-id", 0,
                "tenant/clip.aac", "audio/aac", bytes.length, sha256, null, 35_000L,
                null, "RECEIVED", "PENDING", recordedFrom, recordedTo, now.minusSeconds(5));
        when(mediaVerifier.verify("audio/aac", bytes))
                .thenReturn(Verification.structurallyValid(35_000L, "audio/aac"));
        when(recordingRepository.findClipBySha256(tenantId, sessionId, sha256))
                .thenReturn(Optional.of(existing));

        var result = service.uploadClip(visitId,
                new MockMultipartFile("file", "replay.aac", "audio/aac", bytes),
                "changed-client-id", 35_000L, recordedFrom, recordedTo);

        assertThat(result.clipId()).isEqualTo(existing.id());
        verify(recordingRepository).flagSessionForReview(eq(tenantId), eq(sessionId), any());
        verify(fileStorage, never()).put(any(), any());
        verify(recordingRepository, never()).insertClip(any(), any(), any(), any(),
                anyInt(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(recordingRepository, never()).incrementSessionClipCount(any(), any());
    }

    @Test
    void sameDecodedPcmWithDifferentContainerBytesDoesNotAccumulateAgain() {
        Instant recordedTo = now.minusSeconds(10);
        Instant recordedFrom = recordedTo.minusSeconds(35);
        byte[] changedContainerBytes = new byte[] {0x11, 0x22, 0x44};
        String decodedContentHash = "pcm-sha256-v1:" + "a".repeat(64);
        var existing = new RecordingClipRow(UUID.randomUUID(), sessionId, "first-container", 0,
                "tenant/clip.aac", "audio/aac", 3L, "other-file-sha", decodedContentHash,
                35_000L, 35_000L, "RECEIVED", "VERIFIED",
                recordedFrom, recordedTo, now.minusSeconds(5));
        when(mediaVerifier.verify("audio/m4a", changedContainerBytes))
                .thenReturn(Verification.verified(35_000L, "audio/m4a", decodedContentHash));
        when(recordingRepository.findClipByDecodedContentHash(
                tenantId, sessionId, decodedContentHash)).thenReturn(Optional.of(existing));

        var result = service.uploadClip(visitId,
                new MockMultipartFile("file", "repacked.m4a", "audio/m4a", changedContainerBytes),
                "changed-container", 35_000L, recordedFrom, recordedTo);

        assertThat(result.clipId()).isEqualTo(existing.id());
        verify(recordingRepository).flagSessionForReview(tenantId, sessionId, now);
        verify(fileStorage, never()).put(any(), any());
        verify(recordingRepository, never()).incrementSessionClipCount(any(), any());
    }

    @Test
    void decodedPcmReusedAcrossSessionsFlagsBothSessionsForReview() {
        Instant recordedTo = now.minusSeconds(10);
        Instant recordedFrom = recordedTo.minusSeconds(35);
        byte[] bytes = new byte[] {0x21, 0x22, 0x23};
        String decodedContentHash = "pcm-sha256-v1:" + "b".repeat(64);
        UUID previousSessionId = UUID.randomUUID();
        var previous = new RecordingClipRow(UUID.randomUUID(), previousSessionId, "previous", 0,
                "tenant/previous.aac", "audio/aac", 3L, "previous-file-sha",
                decodedContentHash, 35_000L, 35_000L, "RECEIVED", "VERIFIED",
                recordedFrom.minusSeconds(60), recordedTo.minusSeconds(60), now.minusSeconds(60));
        when(mediaVerifier.verify("audio/aac", bytes))
                .thenReturn(Verification.verified(35_000L, "audio/aac", decodedContentHash));
        when(recordingRepository.findClipByDecodedContentHashOutsideSession(
                tenantId, sessionId, decodedContentHash)).thenReturn(Optional.of(previous));

        service.uploadClip(visitId,
                new MockMultipartFile("file", "reused.aac", "audio/aac", bytes),
                "cross-session-reuse", 35_000L, recordedFrom, recordedTo);

        verify(recordingRepository).flagSessionForReview(tenantId, previousSessionId, now);
        verify(recordingRepository).flagSessionForReview(tenantId, sessionId, now);
        verify(recordingRepository).insertClip(any(), eq(tenantId), eq(sessionId),
                eq("cross-session-reuse"), eq(0), any(), eq("audio/aac"), eq(3L), any(),
                eq(decodedContentHash), eq(35_000L), eq(35_000L), eq("VERIFIED"),
                eq(recordedFrom), eq(recordedTo), eq(now));
        verify(recordingRepository).incrementSessionClipCount(tenantId, sessionId);
    }

    @Test
    void codecPaddingPastTenMinutesIsAcceptedButNeverCountedAsTrustedTime() {
        Instant recordedTo = now;
        Instant recordedFrom = recordedTo.minusMillis(599_750L);
        byte[] bytes = new byte[] {0x31, 0x32, 0x33};
        String decodedContentHash = "pcm-sha256-v1:" + "c".repeat(64);
        when(visitRepository.findVisit(tenantId, profileId, visitId)).thenReturn(Optional.of(
                new VisitSnapshot(visitId, UUID.randomUUID(), profileId, "MY_STORE",
                        UUID.randomUUID(), UUID.randomUUID(), "CHECKED_IN",
                        now.minusSeconds(700), null, UUID.randomUUID(), now.minusSeconds(700),
                        null, null, null, null, null, null, null, null, null)));
        when(mediaVerifier.verify("audio/m4a", bytes))
                .thenReturn(Verification.verified(600_020L, "audio/m4a", decodedContentHash));

        service.uploadClip(visitId,
                new MockMultipartFile("file", "boundary.m4a", "audio/m4a", bytes),
                "ten-minute-boundary", 600_000L, recordedFrom, recordedTo);

        verify(recordingRepository).insertClip(any(), eq(tenantId), eq(sessionId),
                eq("ten-minute-boundary"), eq(0), any(), eq("audio/m4a"), eq(3L), any(),
                eq(decodedContentHash), eq(600_000L), eq(599_750L), eq("VERIFIED"),
                eq(recordedFrom), eq(recordedTo), eq(now));
    }

    @Test
    void clientThirtySecondsCannotStoreActuallyDecodedTwentyFiveSecondClip() {
        Instant recordedTo = now.minusSeconds(10);
        Instant recordedFrom = recordedTo.minusSeconds(30);
        byte[] bytes = new byte[] {0x41, 0x42, 0x43};
        when(mediaVerifier.verify("audio/aac", bytes)).thenReturn(Verification.verified(
                25_000L, "audio/aac", "pcm-sha256-v1:" + "d".repeat(64)));

        assertThatThrownBy(() -> service.uploadClip(visitId,
                new MockMultipartFile("file", "too-short.aac", "audio/aac", bytes),
                "decoded-too-short", 30_000L, recordedFrom, recordedTo))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_RECORDING_INVALID);
                    assertThat(error.getMessage()).contains("服务端核验录音片段不足 30 秒");
                });

        verify(fileStorage, never()).put(any(), any());
        verify(recordingRepository, never()).ensureSession(any(), any(), any(), any());
        verify(recordingRepository, never()).insertClip(any(), any(), any(), any(),
                anyInt(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void durationMismatchCannotStoreActuallyDecodedTwentySecondClip() {
        Instant recordedTo = now.minusSeconds(10);
        Instant recordedFrom = recordedTo.minusSeconds(30);
        byte[] bytes = new byte[] {0x61, 0x62, 0x63};
        when(mediaVerifier.verify("audio/aac", bytes)).thenReturn(Verification.verified(
                20_000L, "audio/aac", "pcm-sha256-v1:" + "f".repeat(64)));

        assertThatThrownBy(() -> service.uploadClip(visitId,
                new MockMultipartFile("file", "mismatch-too-short.aac", "audio/aac", bytes),
                "mismatch-decoded-too-short", 30_000L, recordedFrom, recordedTo))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SALES_RECORDING_INVALID);
                    assertThat(error.getMessage()).contains("服务端核验录音片段不足 30 秒");
                });

        verify(fileStorage, never()).put(any(), any());
        verify(recordingRepository, never()).ensureSession(any(), any(), any(), any());
        verify(recordingRepository, never()).insertClip(any(), any(), any(), any(),
                anyInt(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void oneAacFrameQuantizationBelowMinimumDoesNotRejectTheUpload() {
        Instant recordedTo = now.minusSeconds(10);
        Instant recordedFrom = recordedTo.minusSeconds(30);
        byte[] bytes = new byte[] {0x51, 0x52, 0x53};
        String decodedContentHash = "pcm-sha256-v1:" + "e".repeat(64);
        when(mediaVerifier.verify("audio/aac", bytes))
                .thenReturn(Verification.verified(29_980L, "audio/aac", decodedContentHash));

        service.uploadClip(visitId,
                new MockMultipartFile("file", "frame-boundary.aac", "audio/aac", bytes),
                "minimum-frame-boundary", 30_000L, recordedFrom, recordedTo);

        verify(recordingRepository).insertClip(any(), eq(tenantId), eq(sessionId),
                eq("minimum-frame-boundary"), eq(0), any(), eq("audio/aac"), eq(3L), any(),
                eq(decodedContentHash), eq(30_000L), eq(29_980L), eq("VERIFIED"),
                eq(recordedFrom), eq(recordedTo), eq(now));
    }

    @Test
    void decodedDurationDifferenceAtFiveSecondsRemainsVerifiedAndIsCapped() {
        Instant recordedTo = now.minusSeconds(10);
        Instant recordedFrom = recordedTo.minusSeconds(30);
        byte[] bytes = new byte[] {0x71, 0x72, 0x73};
        String decodedContentHash = "pcm-sha256-v1:" + "1".repeat(64);
        when(mediaVerifier.verify("audio/aac", bytes))
                .thenReturn(Verification.verified(35_000L, "audio/aac", decodedContentHash));

        service.uploadClip(visitId,
                new MockMultipartFile("file", "tolerance-edge.aac", "audio/aac", bytes),
                "duration-tolerance-edge", 30_000L, recordedFrom, recordedTo);

        verify(recordingRepository).insertClip(any(), eq(tenantId), eq(sessionId),
                eq("duration-tolerance-edge"), eq(0), any(), eq("audio/aac"), eq(3L), any(),
                eq(decodedContentHash), eq(30_000L), eq(30_000L), eq("VERIFIED"),
                eq(recordedFrom), eq(recordedTo), eq(now));
    }

    @Test
    void decodedDurationDifferenceBeyondFiveSecondsRequiresReview() {
        Instant recordedTo = now.minusSeconds(10);
        Instant recordedFrom = recordedTo.minusSeconds(30);
        byte[] bytes = new byte[] {0x01, 0x12, 0x23};
        String decodedContentHash = "pcm-sha256-v1:" + "2".repeat(64);
        when(mediaVerifier.verify("audio/aac", bytes))
                .thenReturn(Verification.verified(35_001L, "audio/aac", decodedContentHash));

        service.uploadClip(visitId,
                new MockMultipartFile("file", "tolerance-exceeded.aac", "audio/aac", bytes),
                "duration-tolerance-exceeded", 30_000L, recordedFrom, recordedTo);

        verify(recordingRepository).insertClip(any(), eq(tenantId), eq(sessionId),
                eq("duration-tolerance-exceeded"), eq(0), any(), eq("audio/aac"), eq(3L), any(),
                eq(decodedContentHash), eq(30_000L), isNull(), eq("DURATION_MISMATCH"),
                eq(recordedFrom), eq(recordedTo), eq(now));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
