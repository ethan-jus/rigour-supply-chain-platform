package com.rigour.sales.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 销售管理端只读统计契约；指标来自 Sales Work 事实，不在 Portal 现算。 */
public final class SalesWorkManagementApiModels {

    private SalesWorkManagementApiModels() {
    }

    public record ManagementDashboardTotals(
            long activeSalesCount, long attendedSalesCount, long workingSalesCount,
            long finishedWorkDayCount, long totalInterruptionCount,
            long totalVisitCount, long completedVisitCount, long effectiveVisitCount,
            long pendingReviewVisitCount, long firstVisitCount, long revisitCount,
            long uniqueStoreCount, long assignedStoreCount) {
    }

    public record SalesPersonActivityView(
            UUID salesProfileId, UUID employeeId, String salesNo, boolean working,
            long workDayCount, long totalVisitCount, long completedVisitCount,
            long effectiveVisitCount, long pendingReviewVisitCount,
            long firstVisitCount, long revisitCount, long uniqueStoreCount,
            long assignedStoreCount) {
    }

    public record ManagementDashboardView(
            LocalDate from, LocalDate to, Instant generatedAt,
            ManagementDashboardTotals totals, List<SalesPersonActivityView> people) {
        public ManagementDashboardView {
            people = people == null ? List.of() : List.copyOf(people);
        }
    }

    public record VisitPlanProfileOptionView(
            UUID salesProfileId, UUID employeeId, String salesNo, UUID cityOrgId) {
    }

    public record ManagementVisitPlanView(
            UUID planId, UUID salesProfileId, String salesNo, LocalDate plannedDate,
            String targetType, UUID customerId, UUID storeId,
            String customerName, String storeName, String storeAddress,
            String objective, String status, UUID visitId, long version,
            Instant createdAt, Instant updatedAt) {
    }

    public record ManagementVisitPlanPageView(
            LocalDate from, LocalDate to, String status,
            List<ManagementVisitPlanView> items, int page, int pageSize, long total) {
        public ManagementVisitPlanPageView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** 新建时 version 为空；修改时必须回传当前 version 做乐观锁。 */
    public record UpsertVisitPlanCommand(
            UUID salesProfileId, LocalDate plannedDate, UUID storeId,
            String objective, Long version) {
    }

    public record CancelVisitPlanCommand(Long version) {
    }

    public record VisitReviewQueueItemView(
            UUID visitId, UUID salesProfileId, String salesNo, String storeName,
            Instant checkedInAt, Instant checkedOutAt, int dwellMinutes,
            int minimumDwellMinutes, int uploadedRecordingSeconds, int verifiedRecordingSeconds,
            int minimumRecordingSeconds, int verifiedStorefrontPhotoCount, int requiredStorefrontPhotoCount,
            String contactOutcome, String kpName, String intentionLevel,
            String resultNote, String visitType, List<String> anomalyCodes) {
        public VisitReviewQueueItemView {
            anomalyCodes = anomalyCodes == null ? List.of() : List.copyOf(anomalyCodes);
        }
    }

    public record VisitReviewQueueView(
            LocalDate from, LocalDate to, List<VisitReviewQueueItemView> items,
            int page, int pageSize, long total) {
        public VisitReviewQueueView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ManagementRecordingClipView(
            UUID clipId, int clipIndex, long objectSizeBytes,
            Long clientDurationMs, String uploadStatus, Instant createdAt) {
    }

    public record ManagementRecordingSessionView(
            UUID visitId, int clipCount, long uploadedTotalDurationMs,
            List<ManagementRecordingClipView> clips) {
        public ManagementRecordingSessionView {
            clips = clips == null ? List.of() : List.copyOf(clips);
        }
    }

    public record ManagementPhotoEvidenceView(
            UUID evidenceId, String evidenceRole, String captureSource, Instant capturedAt,
            String mediaType, long objectSizeBytes, BigDecimal distanceToTargetMeters,
            String evidenceStatus, Instant serverReceivedAt) {
    }

    public record ManagementVisitEvidenceView(
            UUID visitId, int requiredStorefrontPhotoCount, int verifiedStorefrontPhotoCount,
            List<ManagementPhotoEvidenceView> photos) {
        public ManagementVisitEvidenceView {
            photos = photos == null ? List.of() : List.copyOf(photos);
        }
    }

    /** 主管复核决定；相同决定重复提交幂等返回，已形成相反结论时拒绝覆盖。 */
    public record ReviewVisitCommand(String decision, String reasonCode, String reviewNote) {
    }

    public record ReviewVisitResultView(
            UUID visitId, String decision, String reasonCode, Instant finalizedAt) {
    }
}
