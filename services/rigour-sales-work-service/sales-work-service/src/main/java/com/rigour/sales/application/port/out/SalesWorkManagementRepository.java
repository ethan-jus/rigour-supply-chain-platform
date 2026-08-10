package com.rigour.sales.application.port.out;

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
                      java.time.Instant decidedAt);

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
            int minimumDwellMinutes, int uploadedRecordingSeconds, int minimumRecordingSeconds,
            String contactOutcome, String kpName, String intentionLevel,
            String resultNote, String visitType) {
    }

    record ReviewTargetRow(
            UUID visitId, String finalReasonCode, String reviewReasonCode,
            java.time.Instant finalizedAt) {
    }
}
