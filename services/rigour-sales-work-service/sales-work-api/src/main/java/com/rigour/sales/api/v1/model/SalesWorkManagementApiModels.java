package com.rigour.sales.api.v1.model;

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

    public record VisitReviewQueueItemView(
            UUID visitId, UUID salesProfileId, String salesNo, String storeName,
            Instant checkedInAt, Instant checkedOutAt, int dwellMinutes,
            int minimumDwellMinutes, int uploadedRecordingSeconds, int minimumRecordingSeconds,
            String contactOutcome, String kpName, String intentionLevel,
            String resultNote, String visitType) {
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

    /** 主管复核决定；相同决定重复提交幂等返回，已形成相反结论时拒绝覆盖。 */
    public record ReviewVisitCommand(String decision, String reasonCode, String reviewNote) {
    }

    public record ReviewVisitResultView(
            UUID visitId, String decision, String reasonCode, Instant finalizedAt) {
    }
}
