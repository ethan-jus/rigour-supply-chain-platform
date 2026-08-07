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
            int offlineUploadDeadlineMinutes) {
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

    public record LocationBatchResult(
            UUID workDayId, int acceptedCount, int duplicateCount, int rejectedCount,
            Instant lastReceivedAt) {
    }
}
