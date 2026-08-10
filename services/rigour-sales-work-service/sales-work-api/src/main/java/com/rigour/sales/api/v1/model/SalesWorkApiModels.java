package com.rigour.sales.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Sales Work V1 H5作业契约模型；不暴露数据库记录和跨域内部字段。 */
public final class SalesWorkApiModels {

    private SalesWorkApiModels() {
    }

    public record FieldPolicyView(
            UUID id, String policyCode, String policyName, int versionNo, String publishStatus,
            String timezoneId, LocalTime businessDayCutoff,
            LocalTime checkInWindowStart, LocalTime checkInWindowEnd,
            LocalTime checkOutWindowStart, LocalTime checkOutWindowEnd,
            int standardWorkMinutes, int minimumWorkMinutes, boolean requireCheckOut,
            boolean allowAdjustment, Integer adjustmentDeadlineHours, boolean locationEnabled,
            int locationIntervalMinutes, BigDecimal minimumLocationAccuracyMeters,
            int offlineUploadDeadlineMinutes, Instant effectiveFrom) {
    }

    public record SalesContextView(
            UUID userId, UUID employeeId, UUID salesProfileId, String salesNo, UUID cityOrgId,
            String profileStatus, Set<String> permissions, FieldPolicyView fieldPolicy) {
        public SalesContextView {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }

    public record VisitTargetView(
            UUID projectionId, String targetType, UUID customerId, UUID storeId,
            String customerName, String storeName, String storeAddress,
            BigDecimal longitude, BigDecimal latitude, String storeStatus,
            long sourceVersion, Instant sourceUpdatedAt) {
    }

    public record VisitTargetPageView(List<VisitTargetView> items, int page, int pageSize, long total) {
        public VisitTargetPageView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record LocationEvidence(
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters, String source) {
    }

    public record CheckInCommand(
            String idempotencyKey, String clientInstanceId, Instant clientOccurredAt,
            LocationEvidence location, String deviceIdHash, String networkType) {
    }

    public record LocationPointCommand(
            String deviceEventId, BigDecimal longitude, BigDecimal latitude,
            BigDecimal accuracyMeters, Instant clientOccurredAt, String source) {
    }

    public record LocationBatchCommand(String idempotencyKey, List<LocationPointCommand> points) {
        public LocationBatchCommand {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record CheckOutCommand(
            String idempotencyKey, Instant clientOccurredAt, LocationEvidence location,
            String deviceIdHash, String networkType) {
    }

    public record InterruptionCommand(
            String idempotencyKey, String interruptionType, Instant startedAt,
            Instant endedAt, Integer durationSeconds, String clientDetail) {
    }

    public record WorkDayView(
            UUID id, UUID employeeId, UUID salesProfileId, LocalDate businessDate,
            String timezoneId, UUID fieldPolicyVersionId, String status,
            Instant checkedInAt, Instant checkedOutAt, UUID locationSessionId,
            int locationPointCount, int interruptionCount, int verifiedWorkMinutes,
            String evidenceQuality) {
    }

    /** 月历中的单日事实；scheduleStatus=UNKNOWN时不得据此推断休息或旷工。 */
    public record AttendanceMonthDayView(
            LocalDate businessDate, UUID workDayId, String scheduleStatus, String attendanceStatus,
            Instant checkedInAt, Instant checkedOutAt, int verifiedWorkMinutes,
            Integer minimumWorkMinutes, String evidenceQuality) {
    }

    public record AttendanceMonthView(
            String month, LocalDate today, List<AttendanceMonthDayView> days) {
        public AttendanceMonthView {
            days = days == null ? List.of() : List.copyOf(days);
        }
    }

    public record LocationBatchResult(
            UUID workDayId, int acceptedCount, int duplicateCount, int rejectedCount,
            Instant lastReceivedAt) {
    }

    public record NearbyStoreView(
            String poiId, String name, String address, String type, String typeCode,
            BigDecimal longitude, BigDecimal latitude, BigDecimal distanceMeters,
            UUID storeId, boolean alreadyInMyStores, String source) {
    }

    public record NearbyStorePageView(List<NearbyStoreView> items, int page, int pageSize, long total) {
        public NearbyStorePageView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record PoiTargetCommand(
            String poiId, String name, String address,
            BigDecimal longitude, BigDecimal latitude, BigDecimal distanceMeters) {
    }

    public record CreateVisitCommand(
            String idempotencyKey, UUID workDayId, String targetType, UUID storeId,
            PoiTargetCommand poi, LocationEvidence location,
            Instant clientOccurredAt, String deviceEventId) {
    }

    public record CheckOutVisitCommand(
            String idempotencyKey, Instant clientOccurredAt, LocationEvidence location,
            String deviceEventId) {
    }

    public record VisitTargetSnapshotView(
            String targetType, UUID customerId, UUID storeId, String customerName,
            String storeName, String storeAddress, BigDecimal longitude, BigDecimal latitude,
            UUID assignedSalesProfileId) {
    }

    public record VisitCheckpointView(
            UUID id, String checkpointType, String deviceEventId,
            Instant clientOccurredAt, Instant serverReceivedAt,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            BigDecimal distanceToTargetMeters, String evidenceStatus) {
    }

    public record VisitView(
            UUID id, UUID workDayId, UUID salesProfileId, String targetType,
            UUID customerId, UUID storeId, String status,
            Instant checkedInAt, Instant checkedOutAt, UUID visitPolicyVersionId,
            VisitTargetSnapshotView targetSnapshot, List<VisitCheckpointView> checkpoints,
            Instant createdAt, String contactOutcome, String kpName, String kpPhone, String intentionLevel,
            String resultNote, Instant resultSubmittedAt, String visitType, String reviewStatus) {
        public VisitView {
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        }
    }

    /** 拜访结果采集：实际接触时填写KP资料；关门、KP不在等未接触场景必须填写现场说明。 */
    public record VisitResultCommand(
            String contactOutcome, String kpName, String kpPhone, String intentionLevel, String resultNote) {
    }

    public record RecordingClipView(
            UUID clipId, UUID sessionId, String clientClipId, int clipIndex, long objectSizeBytes,
            Long clientDurationMs, String uploadStatus, Instant createdAt) {
    }

    /** 短录音只登记审计元数据，不保存音频字节，也不计入有效录音时长。 */
    public record DiscardRecordingClipCommand(
            String clientClipId, Long durationMs, Instant recordedFrom, Instant recordedTo,
            String reason) {
    }

    public record DiscardRecordingClipView(
            String clientClipId, Long durationMs, String disposition, Instant recordedAt) {
    }

    public record RecordingSessionView(
            UUID sessionId, UUID visitId, String status, int clipCount,
            long uploadedTotalDurationMs, long verifiedTotalDurationMs,
            boolean recordingEnabled, int minimumRecordingSeconds, int minimumClipSeconds,
            List<RecordingClipView> clips) {
        public RecordingSessionView {
            clips = clips == null ? List.of() : List.copyOf(clips);
        }
    }

    public record VisitPageView(List<VisitView> items, int page, int pageSize, long total) {
        public VisitPageView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** 销售本人某一日期区间的执行摘要；有效拜访只统计已经完成最终复核的事实。 */
    public record VisitActivitySummaryView(
            LocalDate from, LocalDate to, long totalVisitCount, long completedVisitCount,
            long inProgressVisitCount, long effectiveVisitCount, long pendingReviewVisitCount,
            long firstVisitCount, long revisitCount, long uniqueStoreCount,
            long assignedStoreCount) {
    }

    /** 当日轨迹定位点；按服务端接收时间升序。 */
    public record TrackPointView(
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            Instant clientOccurredAt, Instant serverReceivedAt, String source, String qualityStatus) {
    }

    /** 签到/签退等 Punch 打点；位置为打卡时实时定位证据。 */
    public record TrackPunchView(
            String eventType, Instant clientOccurredAt, Instant serverReceivedAt,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters, String evidenceStatus) {
    }

    /** 轨迹地图上的拜访节点；门店位置来自拜访发生时快照。 */
    public record TrackVisitView(
            UUID visitId, int sequence, UUID storeId, String storeName,
            BigDecimal longitude, BigDecimal latitude, String status,
            Instant checkedInAt, Instant checkedOutAt, int dwellMinutes,
            String visitType, String reviewStatus) {
    }

    /** 相邻两次拜访之间的行程；优先按定位轨迹累计，无定位点时降级为直线距离。 */
    public record TrackTravelSegmentView(
            UUID fromVisitId, UUID toVisitId, int fromSequence, int toSequence,
            String fromStoreName, String toStoreName, int distanceMeters,
            int durationMinutes, String distanceSource) {
    }

    public record WorkDayTrackView(
            UUID workDayId, LocalDate businessDate, String status,
            int totalDistanceMeters, int trackedDurationMinutes,
            List<TrackPointView> points, List<TrackPunchView> punches,
            List<TrackVisitView> visits, List<TrackTravelSegmentView> segments) {
        public WorkDayTrackView {
            points = points == null ? List.of() : List.copyOf(points);
            punches = punches == null ? List.of() : List.copyOf(punches);
            visits = visits == null ? List.of() : List.copyOf(visits);
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }
}
