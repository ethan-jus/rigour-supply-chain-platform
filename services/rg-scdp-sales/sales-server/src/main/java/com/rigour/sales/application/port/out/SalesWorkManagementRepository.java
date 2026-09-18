package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 销售管理只读聚合端口；所有查询必须携带 tenantId。 */
public interface SalesWorkManagementRepository {

    ManagementTotalsRow totals(UUID tenantId, LocalDate from, LocalDate to);

    List<SalesPersonActivityRow> people(UUID tenantId, LocalDate from, LocalDate to);

    List<VisitReviewRow> reviewQueue(UUID tenantId, LocalDate from, LocalDate to,
                                     int limit, int offset);

    long countReviewQueue(UUID tenantId, LocalDate from, LocalDate to);

    java.util.Optional<ReviewTargetRow> findReviewTarget(UUID tenantId, UUID visitId, boolean lock);

    int finalizeVisit(UUID tenantId, UUID visitId, String decision, String reasonCode,
                      java.time.Instant finalizedAt);

    void insertReview(UUID id, UUID tenantId, UUID visitId, UUID reviewerId,
                      String decision, String reasonCode, String reviewNote,
                      Instant decidedAt);

    /** 锁定拜访后读取自动判定所需的服务端事实，不接收客户端计算结论。 */
    java.util.Optional<VisitAssessmentRow> findVisitAssessment(UUID tenantId, UUID visitId);

    /** 保存当前自动判定结果；异常仍由主管在既有复核接口形成最终结论。 */
    void upsertAutomaticAssessment(UUID id, UUID tenantId, UUID visitId,
                                   String reviewStatus, String decision,
                                   String reasonCode, String assessmentNote, Instant assessedAt);

    record ManagementTotalsRow(
            long activeSalesCount, long attendedSalesCount, long workingSalesCount,
            long finishedWorkDayCount, long totalInterruptionCount,
            long totalVisitCount, long completedVisitCount, long effectiveVisitCount,
            long pendingReviewVisitCount, long firstVisitCount, long revisitCount,
            long uniqueStoreCount, long assignedStoreCount) {
    }

    record SalesPersonActivityRow(
            UUID salesProfileId, UUID employeeId, String salesNo, boolean working,
            long workDayCount, long totalVisitCount, long completedVisitCount,
            long effectiveVisitCount, long pendingReviewVisitCount,
            long firstVisitCount, long revisitCount, long uniqueStoreCount,
            long assignedStoreCount) {
    }

    record VisitReviewRow(
            UUID visitId, UUID salesProfileId, String salesNo, String storeName,
            java.time.Instant checkedInAt, java.time.Instant checkedOutAt, int dwellMinutes,
            int minimumDwellMinutes, int uploadedRecordingSeconds, int verifiedRecordingSeconds,
            int minimumRecordingSeconds, int verifiedStorefrontPhotoCount,
            int requiredStorefrontPhotoCount,
            String contactOutcome, String kpName, String intentionLevel,
            String resultNote, String visitType, List<String> anomalyCodes) {
        public VisitReviewRow {
            anomalyCodes = anomalyCodes == null ? List.of() : List.copyOf(anomalyCodes);
        }
    }

    record ReviewTargetRow(
            UUID visitId, String finalReasonCode, String reviewReasonCode,
            Instant finalizedAt) {
    }

    record VisitAssessmentRow(
            UUID visitId, Instant checkedInAt, Instant checkedOutAt, Instant finalizedAt,
            String contactOutcome, Instant resultSubmittedAt,
            int minimumDwellMinutes, int requiredPhotoCount, long verifiedStorefrontPhotoCount,
            boolean recordingEnabled, int minimumRecordingSeconds,
            Long verifiedRecordingDurationMs, String recordingEvidenceStatus,
            boolean aiAsrEnabled, boolean aiRelevanceEnabled, boolean aiDuplicateEnabled,
            BigDecimal aiAutoConfirmThreshold, String aiRecommendation,
            BigDecimal aiConfidenceScore, boolean aiResultAdopted) {
    }
}
