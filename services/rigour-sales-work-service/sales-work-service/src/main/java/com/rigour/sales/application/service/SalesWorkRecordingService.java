package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.RecordingClipView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.RecordingSessionView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipView;
import com.rigour.sales.application.port.out.SalesWorkRecordingRepository;
import com.rigour.sales.application.port.out.RecordingMediaVerifier;
import com.rigour.sales.application.port.out.SalesWorkRecordingRepository.RecordingClipRow;
import com.rigour.sales.application.port.out.SalesWorkRecordingRepository.RecordingDiscardRow;
import com.rigour.sales.application.port.out.SalesWorkRecordingRepository.RecordingSessionRow;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
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
import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拜访录音采集用例：片段字节走 FileStorage，会话/片段事实落 Sales Work 库。
 * 录音由销售在拜访中主动开启；ADTS AAC由服务端解析真实时长，其他格式等待后续验证，不做客户端自证。
 */
@Service
public class SalesWorkRecordingService {

    private static final long MAX_CLIP_DURATION_MS = 600_000L;
    private static final long DURATION_CLOCK_TOLERANCE_MS = 5_000L;

    private static final Map<String, String> AUDIO_EXTENSIONS = Map.of(
            "audio/m4a", ".m4a",
            "audio/aac", ".aac",
            "audio/mp4", ".m4a",
            "audio/mpeg", ".mp3",
            "audio/wav", ".wav",
            "audio/x-wav", ".wav",
            "audio/amr", ".amr",
            "application/octet-stream", ".m4a");

    private final SalesWorkRecordingRepository recordingRepository;
    private final SalesWorkVisitRepository visitRepository;
    private final SalesWorkQueryRepository queryRepository;
    private final SalesWorkContextService contextService;
    private final RecordingMediaVerifier mediaVerifier;
    private final SalesWorkVisitAssessmentService assessmentService;
    private final FileStorage fileStorage;
    private final SalesRecordingProperties properties;
    private final AuditSink auditSink;
    private final Clock clock;

    public SalesWorkRecordingService(SalesWorkRecordingRepository recordingRepository,
                                     SalesWorkVisitRepository visitRepository,
                                     SalesWorkQueryRepository queryRepository,
                                     SalesWorkContextService contextService,
                                     RecordingMediaVerifier mediaVerifier,
                                     SalesWorkVisitAssessmentService assessmentService,
                                     FileStorage fileStorage,
                                     SalesRecordingProperties properties,
                                     AuditSink auditSink,
                                     Clock clock) {
        this.recordingRepository = recordingRepository;
        this.visitRepository = visitRepository;
        this.queryRepository = queryRepository;
        this.contextService = contextService;
        this.mediaVerifier = mediaVerifier;
        this.assessmentService = assessmentService;
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    @Transactional
    public RecordingClipView uploadClip(UUID visitId, MultipartFile file, String clientClipId, Long durationMs,
                                        Instant recordedFrom, Instant recordedTo) {
        CallerIdentity caller = requireCaller("sales:recording:own:write");
        if (visitId == null || file == null || file.isEmpty()) {
            throw invalid("录音文件不能为空");
        }
        if (file.getSize() > properties.getMaxClipBytes()) {
            throw invalid("录音片段超过大小限制 " + properties.getMaxClipBytes() + " 字节");
        }
        String normalizedClientClipId = normalizeClientClipId(clientClipId);
        validateTiming(durationMs, recordedFrom, recordedTo);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);
        VisitSnapshot visit = visitRepository
                .findVisit(caller.tenantId(), identity.profile().id(), visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND));
        if (!"CHECKED_IN".equals(visit.status()) && !"CHECKED_OUT".equals(visit.status())) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE, "当前拜访状态不允许上传录音", List.of());
        }
        if (recordedFrom.isBefore(visit.checkedInAt().minusSeconds(60))) {
            throw invalid("录音开始时间早于本次拜访");
        }
        Instant latestAllowed = visit.checkedOutAt() == null ? receivedAt.plusSeconds(60)
                : visit.checkedOutAt().plusSeconds(60);
        if (recordedTo.isAfter(latestAllowed)) {
            throw invalid("录音结束时间超出本次拜访范围");
        }
        long minimumClipDurationMs = minimumClipDurationMs();
        if (durationMs < minimumClipDurationMs) {
            throw invalid("录音片段不足 " + properties.getMinimumClipSeconds() + " 秒，不保存且不计入有效时长");
        }

        byte[] bytes = readBytes(file);
        String sha256 = sha256Hex(bytes);
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (!AUDIO_EXTENSIONS.containsKey(contentType)) {
            throw invalid("不支持的录音媒体类型: " + contentType);
        }
        var verification = mediaVerifier.verify(contentType, bytes);
        if ("INVALID".equals(verification.status())) {
            throw invalid("录音文件不是完整有效的AAC音频");
        }
        Long verifiedDurationMs = verification.verifiedDurationMs();
        String verifyStatus = "VERIFIED".equals(verification.status()) ? "VERIFIED" : "PENDING";
        if ("VERIFIED".equals(verifyStatus)
                && Math.abs(verifiedDurationMs - durationMs) > durationVerificationTolerance(durationMs)) {
            verifyStatus = "DURATION_MISMATCH";
        }
        UUID sessionId = recordingRepository.ensureSession(UUID.randomUUID(), caller.tenantId(),
                visitId, receivedAt);
        recordingRepository.lockSession(caller.tenantId(), sessionId);
        var existing = recordingRepository.findClipByClientId(
                caller.tenantId(), sessionId, normalizedClientClipId);
        if (existing.isPresent()) {
            RecordingClipRow row = existing.get();
            if (!Objects.equals(row.sha256(), sha256)
                    || !Objects.equals(row.clientDurationMs(), durationMs)) {
                throw invalid("clientClipId已被不同录音内容使用");
            }
            return clipView(row);
        }
        int clipIndex = recordingRepository.nextClipIndex(caller.tenantId(), sessionId);
        UUID clipId = UUID.nameUUIDFromBytes((caller.tenantId() + ":" + visitId + ":"
                + normalizedClientClipId).getBytes(StandardCharsets.UTF_8));
        String objectKey = caller.tenantId() + "/visits/" + visitId + "/clips/" + clipId
                + AUDIO_EXTENSIONS.get(contentType);

        fileStorage.put(new FileMetadata(caller.tenantId().toString(), objectKey,
                file.getOriginalFilename() == null ? objectKey : file.getOriginalFilename(),
                contentType, bytes.length, sha256,
                OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC)), new ByteArrayInputStream(bytes));

        recordingRepository.insertClip(clipId, caller.tenantId(), sessionId, normalizedClientClipId,
                clipIndex, objectKey, contentType, bytes.length, sha256, durationMs,
                verifiedDurationMs, verifyStatus,
                recordedFrom, recordedTo, receivedAt);
        recordingRepository.incrementSessionClipCount(caller.tenantId(), sessionId);
        recordingRepository.refreshSessionVerification(caller.tenantId(), sessionId, receivedAt);
        appendAudit(caller, "SALES_RECORDING_CLIP_UPLOADED", visitId, Map.of(
                "clipIndex", Integer.toString(clipIndex),
                "objectSizeBytes", Long.toString(bytes.length),
                "verifyStatus", verifyStatus));
        if ("CHECKED_OUT".equals(visit.status())) assessmentService.assess(caller, visitId);
        return new RecordingClipView(clipId, sessionId, normalizedClientClipId,
                clipIndex, bytes.length, durationMs,
                "RECEIVED", receivedAt);
    }

    public RecordingSessionView recordings(UUID visitId) {
        CallerIdentity caller = requireCaller("sales:recording:own:read");
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, clock.instant());
        VisitSnapshot visit = visitRepository.findVisit(caller.tenantId(), identity.profile().id(), visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND));
        var policy = queryRepository.findVisitPolicy(caller.tenantId(), visit.visitPolicyVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_POLICY_NOT_FOUND));
        return recordingRepository.findSession(caller.tenantId(), visitId)
                .map(session -> new RecordingSessionView(session.id(), visitId, session.status(),
                        session.evidenceStatus(),
                        session.clipCount(), recordingRepository.uploadedTotalDurationMs(
                                caller.tenantId(), session.id()), session.verifiedTotalDurationMs(),
                        policy.recordingEnabled(), policy.minimumRecordingSeconds(),
                        properties.getMinimumClipSeconds(),
                        recordingRepository.findClips(caller.tenantId(), session.id()).stream()
                                .map(SalesWorkRecordingService::clipView).toList()))
                .orElseGet(() -> new RecordingSessionView(null, visitId, "NOT_STARTED", "PENDING", 0,
                        0L, 0L, policy.recordingEnabled(), policy.minimumRecordingSeconds(),
                        properties.getMinimumClipSeconds(), List.of()));
    }

    /**
     * 登记客户端主动丢弃的短录音。只保留时长、原因和拜访关联审计，不创建录音会话、
     * 不写对象存储，避免误触音频进入敏感证据库。
     */
    @Transactional
    public DiscardRecordingClipView discardClip(UUID visitId, DiscardRecordingClipCommand command) {
        CallerIdentity caller = requireCaller("sales:recording:own:write");
        if (visitId == null || command == null) throw invalid("短录音登记参数不能为空");
        String clientClipId = normalizeClientClipId(command.clientClipId());
        validateDiscardTiming(command.durationMs(), command.recordedFrom(), command.recordedTo());
        if (command.durationMs() >= minimumClipDurationMs()) {
            throw invalid("达到最低时长的录音必须上传，不允许按短录音丢弃");
        }
        if (!"TOO_SHORT".equals(command.reason())) {
            throw invalid("短录音丢弃原因仅支持TOO_SHORT");
        }
        Instant recordedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, recordedAt);
        VisitSnapshot visit = visitRepository
                .findVisit(caller.tenantId(), identity.profile().id(), visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND));
        if (!"CHECKED_IN".equals(visit.status()) && !"CHECKED_OUT".equals(visit.status())) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE,
                    "当前拜访状态不允许登记短录音", List.of());
        }
        if (command.recordedFrom().isBefore(visit.checkedInAt().minusSeconds(60))) {
            throw invalid("录音开始时间早于本次拜访");
        }
        Instant latestAllowed = visit.checkedOutAt() == null ? recordedAt.plusSeconds(60)
                : visit.checkedOutAt().plusSeconds(60);
        if (command.recordedTo().isAfter(latestAllowed)) {
            throw invalid("录音结束时间超出本次拜访范围");
        }
        var existing = recordingRepository.findDiscardByClientId(
                caller.tenantId(), visitId, clientClipId);
        if (existing.isPresent()) {
            RecordingDiscardRow row = existing.get();
            if (row.clientDurationMs() != command.durationMs()
                    || !row.recordedFrom().equals(command.recordedFrom())
                    || !row.recordedTo().equals(command.recordedTo())
                    || !row.reason().equals(command.reason())) {
                throw invalid("clientClipId已被不同短录音登记使用");
            }
            return new DiscardRecordingClipView(row.clientClipId(), row.clientDurationMs(),
                    row.disposition(), row.createdAt());
        }
        UUID discardId = UUID.nameUUIDFromBytes((caller.tenantId() + ":" + visitId + ":discard:"
                + clientClipId).getBytes(StandardCharsets.UTF_8));
        recordingRepository.insertDiscard(discardId, caller.tenantId(), visitId, clientClipId,
                command.durationMs(), command.recordedFrom(), command.recordedTo(),
                command.reason(), recordedAt);
        appendAudit(caller, "SALES_RECORDING_CLIP_DISCARDED_SHORT", visitId, Map.of(
                "clientClipId", clientClipId,
                "durationMs", Long.toString(command.durationMs()),
                "reason", command.reason()));
        return new DiscardRecordingClipView(clientClipId, command.durationMs(),
                "DISCARDED_NOT_STORED", recordedAt);
    }

    private static RecordingClipView clipView(RecordingClipRow row) {
        return new RecordingClipView(row.id(), row.sessionId(), row.clientClipId(), row.clipIndex(),
                row.objectSizeBytes(), row.clientDurationMs(), row.uploadStatus(), row.createdAt());
    }

    private static String normalizeClientClipId(String value) {
        if (value == null || value.isBlank()) throw invalid("clientClipId不能为空");
        String normalized = value.trim();
        if (normalized.length() > 128) throw invalid("clientClipId长度不能超过128");
        return normalized;
    }

    private static void validateTiming(Long durationMs, Instant recordedFrom, Instant recordedTo) {
        if (durationMs == null || durationMs <= 0 || durationMs > MAX_CLIP_DURATION_MS) {
            throw invalid("durationMs必须在1到600000之间");
        }
        if (recordedFrom == null || recordedTo == null || !recordedFrom.isBefore(recordedTo)) {
            throw invalid("recordedFrom和recordedTo必须构成有效时间范围");
        }
        long wallClockDuration = Duration.between(recordedFrom, recordedTo).toMillis();
        if (Math.abs(wallClockDuration - durationMs) > DURATION_CLOCK_TOLERANCE_MS) {
            throw invalid("durationMs与录音起止时间不一致");
        }
    }

    private static void validateDiscardTiming(Long durationMs, Instant recordedFrom, Instant recordedTo) {
        if (durationMs == null || durationMs < 0 || durationMs > MAX_CLIP_DURATION_MS) {
            throw invalid("短录音durationMs必须在0到600000之间");
        }
        if (recordedFrom == null || recordedTo == null || recordedFrom.isAfter(recordedTo)) {
            throw invalid("短录音recordedFrom和recordedTo必须构成有效时间范围");
        }
        long wallClockDuration = Duration.between(recordedFrom, recordedTo).toMillis();
        if (Math.abs(wallClockDuration - durationMs) > DURATION_CLOCK_TOLERANCE_MS) {
            throw invalid("短录音durationMs与录音起止时间不一致");
        }
    }

    private long minimumClipDurationMs() {
        if (properties.getMinimumClipSeconds() <= 0 || properties.getMinimumClipSeconds() > 600) {
            throw new IllegalStateException("sales.recording.minimum-clip-seconds必须在1到600之间");
        }
        return properties.getMinimumClipSeconds() * 1_000L;
    }

    private static long durationVerificationTolerance(long clientDurationMs) {
        return Math.max(DURATION_CLOCK_TOLERANCE_MS, clientDurationMs / 10L);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.SALES_RECORDING_STORAGE_FAILED,
                    "录音片段读取失败", List.of());
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    private CallerIdentity requireCaller(String permission) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private void appendAudit(CallerIdentity caller, String action, UUID visitId,
                             Map<String, String> attributes) {
        auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                caller.userId().toString(), action, "SALES_VISIT", visitId.toString(), attributes,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.SALES_RECORDING_INVALID, message, List.of());
    }
}
