package com.rigour.sales.temporarycheckin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 临时打卡公开表单的输入输出模型，不暴露数据库密钥摘要和对象键。 */
public final class TemporaryCheckinModels {

    private TemporaryCheckinModels() { }

    public record SalespersonOption(UUID id, String name, String city) { }

    public record IdentityVerifyRequest(UUID salespersonId, String city, String personalCode) { }

    public record SalesIdentityView(
            boolean authenticated,
            UUID salespersonId,
            String salespersonName,
            String city,
            Instant expiresAt,
            boolean enforcementEnabled) { }

    public record OptionsResponse(
            List<String> cities,
            List<SalespersonOption> salespersons,
            List<String> storeAttributes,
            List<String> operatingStatuses,
            List<String> areaRanges,
            List<String> businessTypes,
            List<String> intendedBusinesses,
            List<String> cooperationIntents,
            List<String> storeGrades,
            List<String> storeTags) { }

    public record LocationCommand(
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal accuracyMeters,
            Instant capturedAt,
            String note) { }

    public record CreateStoreRequest(
            UUID clientStoreId,
            String city,
            UUID salespersonId,
            String sourcePoiId,
            String sourcePoiName,
            String sourcePoiAddress,
            BigDecimal sourcePoiLongitude,
            BigDecimal sourcePoiLatitude,
            String attribute,
            String name,
            String operatingStatus,
            String contactName,
            String contactPhone,
            String areaRange,
            String facilityCount,
            List<String> businessTypes,
            List<String> intendedBusinesses,
            String cooperationIntent,
            String storeGrade,
            List<String> tags,
            LocationCommand location) { }

    public record StoreView(UUID id, String name, String city, String locationSummary) { }

    public record ResolveLocationRequest(String city, LocationCommand location, String q) {
        public ResolveLocationRequest(String city, LocationCommand location) {
            this(city, location, null);
        }
    }

    public record NearbyStoreView(
            String source,
            UUID storeId,
            String poiId,
            String name,
            String city,
            String address,
            BigDecimal distanceMeters,
            BigDecimal longitude,
            BigDecimal latitude,
            String locationSource,
            boolean checkinEligible,
            String nextAction) { }

    public record LocationContextView(
            String geocodeStatus,
            String address,
            String formattedAddress,
            String adcode,
            Boolean cityMatched,
            String resolvedCity,
            String locationMessage,
            int maxCheckinDistanceMeters,
            int maxCheckinAccuracyMeters,
            int maxLocationAgeMinutes,
            boolean accuracyAccepted,
            boolean freshnessAccepted,
            List<NearbyStoreView> nearbyStores) { }

    public record CreateSubmissionRequest(
            UUID clientSubmissionId,
            String submissionKey,
            String city,
            UUID salespersonId,
            UUID storeId,
            String customerName,
            String customerPhone,
            String visitResult,
            LocationCommand location,
            Boolean privacyAccepted,
            String privacyNoticeVersion) { }

    public record DraftSubmissionView(UUID id, String status, Instant createdAt) { }

    public record MediaUploadView(UUID id, String kind, String status, String sha256, long sizeBytes) { }

    public record MediaDeleteView(UUID id, String kind, String status) { }

    public record CompletedSubmissionView(UUID id, String status, Instant submittedAt) { }

    public record ErrorResponse(String code, String message) { }
}
