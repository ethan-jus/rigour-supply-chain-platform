package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementDashboardTotals;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementDashboardView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementRecordingClipView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementRecordingSessionView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementPhotoEvidenceView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementVisitEvidenceView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.SalesPersonActivityView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ReviewVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ReviewVisitResultView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.VisitReviewQueueItemView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.VisitReviewQueueView;
import com.rigour.sales.application.port.out.SalesWorkManagementRepository;
import com.rigour.sales.application.port.out.SalesWorkEvidenceRepository;
import com.rigour.sales.application.port.out.SalesWorkRecordingRepository;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.file.FileStorage;
import com.rigour.shared.outbox.OutboxMessage;
import com.rigour.shared.outbox.OutboxStore;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/** 销售管理端只读统计用例；Portal 仅展示这里返回的已定义指标。 */
@Service
public class SalesWorkManagementService {

    private final SalesWorkManagementRepository repository;
    private final SalesWorkRecordingRepository recordingRepository;
    private final SalesWorkEvidenceRepository evidenceRepository;
    private final FileStorage fileStorage;
    private final OutboxStore outboxStore;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SalesWorkManagementService(SalesWorkManagementRepository repository,
                                      SalesWorkRecordingRepository recordingRepository,
                                      SalesWorkEvidenceRepository evidenceRepository,
                                      FileStorage fileStorage,
                                      OutboxStore outboxStore,
                                      AuditSink auditSink,
                                      ObjectMapper objectMapper,
                                      Clock clock) {
        this.repository = repository;
        this.recordingRepository = recordingRepository;
        this.evidenceRepository = evidenceRepository;
        this.fileStorage = fileStorage;
        this.outboxStore = outboxStore;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ManagementDashboardView dashboard(LocalDate from, LocalDate to) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:dashboard:read");
        validateRange(from, to);
        var row = repository.totals(caller.tenantId(), from, to);
        var totals = new ManagementDashboardTotals(row.activeSalesCount(), row.attendedSalesCount(),
                row.workingSalesCount(), row.finishedWorkDayCount(), row.totalInterruptionCount(),
                row.totalVisitCount(), row.completedVisitCount(), row.effectiveVisitCount(),
                row.pendingReviewVisitCount(), row.firstVisitCount(), row.revisitCount(),
                row.uniqueStoreCount(), row.assignedStoreCount());
        List<SalesPersonActivityView> people = repository.people(caller.tenantId(), from, to).stream()
                .map(person -> new SalesPersonActivityView(person.salesProfileId(), person.employeeId(),
                        person.salesNo(), person.working(), person.workDayCount(), person.totalVisitCount(),
                        person.completedVisitCount(), person.effectiveVisitCount(),
                        person.pendingReviewVisitCount(), person.firstVisitCount(), person.revisitCount(),
                        person.uniqueStoreCount(), person.assignedStoreCount()))
                .toList();
        return new ManagementDashboardView(from, to, clock.instant(), totals, people);
    }

    public VisitReviewQueueView reviewQueue(LocalDate from, LocalDate to, int page, int pageSize) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:visit:review");
        validateRange(from, to);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw invalid("分页参数无效");
        }
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<VisitReviewQueueItemView> items = repository
                .reviewQueue(caller.tenantId(), from, to, pageSize, offset).stream()
                .map(row -> new VisitReviewQueueItemView(row.visitId(), row.salesProfileId(),
                        row.salesNo(), row.storeName(), row.checkedInAt(), row.checkedOutAt(),
                        row.dwellMinutes(), row.minimumDwellMinutes(), row.uploadedRecordingSeconds(),
                        row.verifiedRecordingSeconds(), row.minimumRecordingSeconds(),
                        row.verifiedStorefrontPhotoCount(), row.requiredStorefrontPhotoCount(),
                        row.contactOutcome(), row.kpName(), row.intentionLevel(), row.resultNote(),
                        row.visitType(), row.anomalyCodes()))
                .toList();
        return new VisitReviewQueueView(from, to, items, page, pageSize,
                repository.countReviewQueue(caller.tenantId(), from, to));
    }

    public ManagementRecordingSessionView reviewRecordings(UUID visitId) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:recording:sensitive:play");
        requireReviewTarget(caller, visitId);
        var session = recordingRepository.findSession(caller.tenantId(), visitId);
        if (session.isEmpty()) {
            return new ManagementRecordingSessionView(visitId, 0, 0L, List.of());
        }
        var clips = recordingRepository.findClips(caller.tenantId(), session.get().id());
        var views = clips.stream()
                .map(clip -> new ManagementRecordingClipView(clip.id(), clip.clipIndex(),
                        clip.objectSizeBytes(), clip.clientDurationMs(), clip.uploadStatus(), clip.createdAt()))
                .toList();
        return new ManagementRecordingSessionView(visitId, views.size(),
                recordingRepository.uploadedTotalDurationMs(caller.tenantId(), session.get().id()), views);
    }

    public RecordingContent reviewRecordingClip(UUID visitId, UUID clipId) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:recording:sensitive:play");
        requireReviewTarget(caller, visitId);
        if (clipId == null) throw invalid("录音片段不能为空");
        var session = recordingRepository.findSession(caller.tenantId(), visitId)
                .orElseThrow(() -> invalid("拜访没有可播放的录音"));
        var clip = recordingRepository.findClips(caller.tenantId(), session.id()).stream()
                .filter(item -> clipId.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> invalid("录音片段不存在"));
        try (var input = fileStorage.open(caller.tenantId().toString(), clip.objectKey())) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length != clip.objectSizeBytes()) {
                throw new IllegalStateException("录音对象大小与登记事实不一致");
            }
            auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                    caller.userId().toString(), "SALES_RECORDING_REVIEW_PLAY", "SALES_VISIT",
                    visitId.toString(), Map.of("clipId", clipId.toString()),
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
            return new RecordingContent(clip.mediaType(), bytes);
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.SALES_RECORDING_STORAGE_FAILED,
                    "录音片段读取失败", List.of());
        }
    }

    public ManagementVisitEvidenceView reviewEvidence(UUID visitId) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:evidence:sensitive:read");
        requireReviewTarget(caller, visitId);
        var assessment = repository.findVisitAssessment(caller.tenantId(), visitId)
                .orElseThrow(() -> invalid("拜访证据规则不存在"));
        var photos = evidenceRepository.findStorefrontPhotos(caller.tenantId(), visitId).stream()
                .map(photo -> new ManagementPhotoEvidenceView(photo.id(), photo.evidenceRole(),
                        photo.captureSource(), photo.capturedAt(), photo.mediaType(), photo.objectSizeBytes(),
                        photo.distanceToTargetMeters(), photo.evidenceStatus(), photo.serverReceivedAt()))
                .toList();
        return new ManagementVisitEvidenceView(visitId, Math.max(1, assessment.requiredPhotoCount()),
                Math.toIntExact(assessment.verifiedStorefrontPhotoCount()), photos);
    }

    public EvidenceContent reviewEvidencePhoto(UUID visitId, UUID evidenceId) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:evidence:sensitive:read");
        requireReviewTarget(caller, visitId);
        if (evidenceId == null) throw invalid("照片证据不能为空");
        var photo = evidenceRepository.findStorefrontPhotos(caller.tenantId(), visitId).stream()
                .filter(item -> evidenceId.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> invalid("照片证据不存在"));
        try (var input = fileStorage.open(caller.tenantId().toString(), photo.objectKey())) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length != photo.objectSizeBytes() || !sha256Hex(bytes).equals(photo.contentHash())) {
                throw new IllegalStateException("门头照对象与登记事实不一致");
            }
            auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                    caller.userId().toString(), "SALES_EVIDENCE_REVIEW_VIEW", "SALES_VISIT",
                    visitId.toString(), Map.of("evidenceId", evidenceId.toString()),
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
            return new EvidenceContent(photo.mediaType(), bytes);
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.SALES_EVIDENCE_STORAGE_FAILED,
                    "门头照片读取失败", List.of());
        } catch (BusinessException error) {
            BusinessException mapped = new BusinessException(ErrorCode.SALES_EVIDENCE_STORAGE_FAILED,
                    "门头照片读取失败", List.of());
            mapped.initCause(error);
            throw mapped;
        }
    }

    @Transactional
    public ReviewVisitResultView reviewVisit(UUID visitId, ReviewVisitCommand command) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission("sales:visit:review");
        if (visitId == null || command == null) throw invalid("复核请求无效");
        String decision = required(command.decision(), "decision", 24).toUpperCase(Locale.ROOT);
        if (!Set.of("EFFECTIVE", "INEFFECTIVE").contains(decision)) {
            throw invalid("decision仅支持EFFECTIVE或INEFFECTIVE");
        }
        String reasonCode = required(command.reasonCode(), "reasonCode", 64).toUpperCase(Locale.ROOT);
        String note = bounded(command.reviewNote(), 2000);
        var target = repository.findReviewTarget(caller.tenantId(), visitId, true)
                .orElseThrow(() -> invalid("待复核拜访不存在"));
        if (target.finalizedAt() != null) {
            if (decision.equals(target.finalReasonCode())) {
                return new ReviewVisitResultView(visitId, decision,
                        target.reviewReasonCode() == null ? reasonCode : target.reviewReasonCode(),
                        target.finalizedAt());
            }
            throw invalid("拜访已形成最终结论，不能覆盖历史决定");
        }
        var decidedAt = clock.instant();
        if (repository.finalizeVisit(caller.tenantId(), visitId, decision, reasonCode, decidedAt) != 1) {
            throw invalid("拜访复核状态已变化，请刷新后重试");
        }
        UUID reviewId = UUID.nameUUIDFromBytes((caller.tenantId() + ":" + visitId + ":" + decision)
                .getBytes(StandardCharsets.UTF_8));
        repository.insertReview(reviewId, caller.tenantId(), visitId, caller.userId(),
                decision, reasonCode, note, decidedAt);
        appendOutbox(caller, visitId, decision, reasonCode, decidedAt);
        appendAudit(caller, visitId, decision, reasonCode, decidedAt);
        return new ReviewVisitResultView(visitId, decision, reasonCode, decidedAt);
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.SALES_ADMIN_INVALID, "日期范围无效", List.of());
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw new BusinessException(ErrorCode.SALES_ADMIN_INVALID, "日期范围不能超过366天", List.of());
        }
    }

    private void requireReviewTarget(CallerIdentity caller, UUID visitId) {
        if (visitId == null || repository.findReviewTarget(caller.tenantId(), visitId, false).isEmpty()) {
            throw invalid("可复核拜访不存在");
        }
    }

    private void appendOutbox(CallerIdentity caller, UUID visitId, String decision,
                              String reasonCode, java.time.Instant decidedAt) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "visitId", visitId.toString(), "decision", decision,
                    "reasonCode", reasonCode, "finalizedAt", decidedAt.toString()));
        } catch (RuntimeException error) {
            throw new IllegalStateException("拜访复核事件序列化失败", error);
        }
        outboxStore.append(new OutboxMessage(UUID.randomUUID(), caller.tenantId().toString(),
                "SALES_VISIT", visitId.toString(), "SalesVisitFinalized", 1, payload,
                OffsetDateTime.ofInstant(decidedAt, ZoneOffset.UTC)));
    }

    private void appendAudit(CallerIdentity caller, UUID visitId, String decision,
                             String reasonCode, java.time.Instant decidedAt) {
        auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                caller.userId().toString(), "SALES_VISIT_REVIEW", "SALES_VISIT", visitId.toString(),
                Map.of("decision", decision, "reasonCode", reasonCode),
                OffsetDateTime.ofInstant(decidedAt, ZoneOffset.UTC)));
    }

    private static String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) throw invalid(field + "不能为空");
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw invalid(field + "长度不能超过" + maxLength);
        return trimmed;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(maxLength, trimmed.length()));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.SALES_ADMIN_INVALID, message, List.of());
    }

    public record RecordingContent(String mediaType, byte[] bytes) {
        public RecordingContent {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record EvidenceContent(String mediaType, byte[] bytes) {
        public EvidenceContent {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
