package com.rigour.sales.application.service;

import com.rigour.sales.application.port.out.SalesWorkManagementRepository;
import com.rigour.sales.application.port.out.SalesWorkManagementRepository.VisitAssessmentRow;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.outbox.OutboxMessage;
import com.rigour.shared.outbox.OutboxStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 基于服务端硬证据判定拜访有效性。证据完整且没有异常时自动确认有效；任何缺口都保留在主管复核队列。
 * 客户端上报的“已完成”或录音时长不直接作为最终事实。
 */
@Service
public class SalesWorkVisitAssessmentService {

    private static final String AUTO_REASON = "AUTO_EVIDENCE_COMPLETE";
    private static final Set<String> POSITIVE_AI_RECOMMENDATIONS = Set.of("AUTO_CONFIRM", "EFFECTIVE");

    private final SalesWorkManagementRepository repository;
    private final OutboxStore outboxStore;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SalesWorkVisitAssessmentService(
            SalesWorkManagementRepository repository,
            OutboxStore outboxStore,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.outboxStore = outboxStore;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AssessmentOutcome assess(CallerIdentity actor, UUID visitId) {
        if (actor == null || actor.tenantId() == null || visitId == null) {
            throw new IllegalArgumentException("自动判定必须携带租户、触发人和拜访");
        }
        var target = repository.findReviewTarget(actor.tenantId(), visitId, true);
        if (target.isEmpty()) return new AssessmentOutcome("NOT_READY", List.of(), null);
        if (target.get().finalizedAt() != null) {
            return new AssessmentOutcome("FINALIZED", List.of(), target.get().finalizedAt());
        }
        VisitAssessmentRow facts = repository.findVisitAssessment(actor.tenantId(), visitId)
                .orElseThrow(() -> new IllegalStateException("已锁定拜访缺少自动判定事实"));
        List<String> anomalies = anomalies(facts);
        Instant assessedAt = clock.instant();
        UUID assessmentId = UUID.nameUUIDFromBytes((actor.tenantId() + ":" + visitId + ":assessment")
                .getBytes(StandardCharsets.UTF_8));
        if (!anomalies.isEmpty()) {
            repository.upsertAutomaticAssessment(assessmentId, actor.tenantId(), visitId,
                    "PENDING", null, anomalies.getFirst(), String.join(",", anomalies), assessedAt);
            return new AssessmentOutcome("PENDING_REVIEW", anomalies, null);
        }

        if (repository.finalizeVisit(actor.tenantId(), visitId, "EFFECTIVE", AUTO_REASON, assessedAt) != 1) {
            return new AssessmentOutcome("FINALIZED", List.of(), assessedAt);
        }
        repository.upsertAutomaticAssessment(assessmentId, actor.tenantId(), visitId,
                "DECIDED", "EFFECTIVE", AUTO_REASON,
                "服务端硬证据完整，自动确认有效", assessedAt);
        appendOutbox(actor, visitId, assessedAt);
        appendAudit(actor, visitId, assessedAt);
        return new AssessmentOutcome("AUTO_EFFECTIVE", List.of(), assessedAt);
    }

    private static List<String> anomalies(VisitAssessmentRow facts) {
        List<String> anomalies = new ArrayList<>();
        if (facts.checkedInAt() == null || facts.checkedOutAt() == null
                || facts.checkedOutAt().isBefore(facts.checkedInAt())) {
            anomalies.add("VISIT_TIME_INVALID");
        }
        if (facts.resultSubmittedAt() == null) anomalies.add("RESULT_MISSING");
        if (!"CONTACTED".equals(facts.contactOutcome())) anomalies.add("CONTACT_NOT_CONFIRMED");
        if (facts.checkedInAt() != null && facts.checkedOutAt() != null
                && Duration.between(facts.checkedInAt(), facts.checkedOutAt()).toMinutes()
                < facts.minimumDwellMinutes()) {
            anomalies.add("DWELL_TOO_SHORT");
        }
        int requiredPhotoCount = Math.max(1, facts.requiredPhotoCount());
        if (facts.verifiedStorefrontPhotoCount() < requiredPhotoCount) {
            anomalies.add("STOREFRONT_PHOTO_MISSING");
        }
        if (facts.recordingEnabled()) {
            if (!"TECHNICALLY_VERIFIED".equals(facts.recordingEvidenceStatus())
                    || facts.verifiedRecordingDurationMs() == null) {
                anomalies.add("RECORDING_UNVERIFIED");
            } else if (facts.verifiedRecordingDurationMs() < facts.minimumRecordingSeconds() * 1_000L) {
                anomalies.add("RECORDING_TOO_SHORT");
            }
        }
        if (facts.aiAsrEnabled() || facts.aiRelevanceEnabled() || facts.aiDuplicateEnabled()) {
            if (!facts.aiResultAdopted()) {
                anomalies.add("AI_PENDING");
            } else if (!POSITIVE_AI_RECOMMENDATIONS.contains(facts.aiRecommendation())) {
                anomalies.add("AI_REVIEW_REQUIRED");
            } else if (belowThreshold(facts.aiConfidenceScore(), facts.aiAutoConfirmThreshold())) {
                anomalies.add("AI_LOW_CONFIDENCE");
            }
        }
        return List.copyOf(anomalies);
    }

    private static boolean belowThreshold(BigDecimal confidence, BigDecimal threshold) {
        return threshold != null && (confidence == null || confidence.compareTo(threshold) < 0);
    }

    private void appendOutbox(CallerIdentity actor, UUID visitId, Instant finalizedAt) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "visitId", visitId.toString(), "decision", "EFFECTIVE",
                    "reasonCode", AUTO_REASON, "finalizedAt", finalizedAt.toString(),
                    "decisionSource", "AUTOMATIC_ASSESSMENT"));
        } catch (RuntimeException error) {
            throw new IllegalStateException("自动判定事件序列化失败", error);
        }
        outboxStore.append(new OutboxMessage(UUID.randomUUID(), actor.tenantId().toString(),
                "SALES_VISIT", visitId.toString(), "SalesVisitFinalized", 1, payload,
                OffsetDateTime.ofInstant(finalizedAt, ZoneOffset.UTC)));
    }

    private void appendAudit(CallerIdentity actor, UUID visitId, Instant finalizedAt) {
        auditSink.append(new AuditEvent(actor.tenantId().toString(), RequestContext.getRequestId(),
                actor.userId().toString(), "SALES_VISIT_AUTO_EFFECTIVE", "SALES_VISIT",
                visitId.toString(), Map.of("reasonCode", AUTO_REASON),
                OffsetDateTime.ofInstant(finalizedAt, ZoneOffset.UTC)));
    }

    public record AssessmentOutcome(String status, List<String> anomalyCodes, Instant finalizedAt) {
        public AssessmentOutcome {
            anomalyCodes = anomalyCodes == null ? List.of() : List.copyOf(anomalyCodes);
        }
    }
}
