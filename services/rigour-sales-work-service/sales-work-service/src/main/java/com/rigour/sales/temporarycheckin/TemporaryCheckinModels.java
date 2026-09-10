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
            boolean enforcementEnabled,
            UUID tenantId) { }

    /**
     * 客户端关键阶段诊断事件。只接收枚举化状态和计数，不接收查询词、文件名、坐标或媒体内容。
     */
    public record ClientDiagnosticEventRequest(
            UUID salespersonId,
            UUID clientEventId,
            String event,
            String result,
            Integer itemCount,
            Long fileSizeBytes) { }

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
            List<String> storeTags,
            long maxAudioBytes) { }

    public record LocationCommand(
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal accuracyMeters,
            Instant capturedAt,
            String note,
            String rawTimestamp,
            Instant receivedAt,
            String source,
            String timeStatus,
            Boolean userReportedInaccurate) {
        public LocationCommand(BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
                Instant capturedAt, String note) {
            this(longitude, latitude, accuracyMeters, capturedAt, note, null, null,
                    "BROWSER_GEOLOCATION", capturedAt == null ? "UNKNOWN" : "KNOWN", false);
        }
    }

    /** 服务器回执与可追加证据期限；不包含提交密钥或对象存储地址。 */
    public record SubmissionReceipt(UUID id, UUID clientSubmissionId, String status,
            String storeName, String city, Instant createdAt, Instant submittedAt,
            List<String> uploadedMedia, List<UUID> audioSegmentIds, Instant supplementUntil,
            String visitResult, Long audioDurationMs, List<UUID> photoIds, List<PhotoView> photos,
            Long audioDisplayDurationMs, String audioDurationSource) { }

    public record SubmissionReceiptPage(List<SubmissionReceipt> items, int page, int size,
            long totalElements, int totalPages) { }

    /** 本人历史详情仅返回业务事实与受鉴权媒体路径，不包含后台风险信息或写入凭据。 */
    public record OwnSubmissionDetail(UUID id, UUID clientSubmissionId, String status, String city,
            UUID salespersonId, String salespersonName, UUID storeId, String storeName,
            String customerName, String customerPhone, String visitResult, Instant createdAt, Instant submittedAt,
            Instant supplementUntil, boolean canSupplement, String locationQuality, Instant locationCapturedAt,
            String locationRawTimestamp, Instant locationReceivedAt, String locationSource, String locationAddress,
            String locationNote, BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            BigDecimal storeLongitude, BigDecimal storeLatitude, BigDecimal distanceMeters, List<OwnMediaView> media,
            List<PhotoView> photos) { }

    /** 来源是客户端明确上报的选择方式，不证明照片拍摄时间；历史未知值保持空值。 */
    public record PhotoView(UUID photoId, String mediaId, String contentType, String originalFilename,
            long sizeBytes, Instant uploadedAt, String captureSource, String thumbnailUrl, String originalUrl) { }

    public record OwnMediaView(String mediaId, String kind, String originalFilename, String contentType,
            long sizeBytes, Instant uploadedAt, Long parsedDurationMs, String playbackStatus,
            String thumbnailUrl, String playbackUrl, String originalUrl,
            Long clientDurationMs, String captureSource, String timingStatus, Instant clientStartedAt,
            Instant fileLastModifiedAt, Long durationMs, String durationSource) {
        public OwnMediaView(String mediaId, String kind, String originalFilename, String contentType,
                long sizeBytes, Instant uploadedAt, Long parsedDurationMs, String playbackStatus,
                String thumbnailUrl, String playbackUrl, String originalUrl) {
            this(mediaId,kind,originalFilename,contentType,sizeBytes,uploadedAt,parsedDurationMs,playbackStatus,
                    thumbnailUrl,playbackUrl,originalUrl,null,null,null,null,null,null,"UNKNOWN");
        }
    }

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
            LocationCommand location,
            String sourcePoiToken,
            String locationVerificationToken,
            String manualEntryToken,
            String locationFailureReason,
            UUID locationAttemptId) {

        public CreateStoreRequest(
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
                LocationCommand location,
                String sourcePoiToken,
                String locationVerificationToken,
                String manualEntryToken) {
            this(clientStoreId, city, salespersonId, sourcePoiId, sourcePoiName, sourcePoiAddress,
                    sourcePoiLongitude, sourcePoiLatitude, attribute, name, operatingStatus,
                    contactName, contactPhone, areaRange, facilityCount, businessTypes,
                    intendedBusinesses, cooperationIntent, storeGrade, tags, location,
                    sourcePoiToken, locationVerificationToken, manualEntryToken, null, null);
        }

        public CreateStoreRequest(
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
                LocationCommand location,
                String sourcePoiToken) {
            this(clientStoreId, city, salespersonId, sourcePoiId, sourcePoiName, sourcePoiAddress,
                    sourcePoiLongitude, sourcePoiLatitude, attribute, name, operatingStatus,
                    contactName, contactPhone, areaRange, facilityCount, businessTypes,
                    intendedBusinesses, cooperationIntent, storeGrade, tags, location,
                    sourcePoiToken, null, null, null, null);
        }

        /** 仅保留旧 Java 调用方的构造兼容；真实请求缺少服务端凭证时仍会被拒绝。 */
        public CreateStoreRequest(
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
                LocationCommand location) {
            this(clientStoreId, city, salespersonId, sourcePoiId, sourcePoiName, sourcePoiAddress,
                    sourcePoiLongitude, sourcePoiLatitude, attribute, name, operatingStatus,
                    contactName, contactPhone, areaRange, facilityCount, businessTypes,
                    intendedBusinesses, cooperationIntent, storeGrade, tags, location,
                    null, null, null, null, null);
        }
    }

    public record StoreView(
            UUID id,
            String name,
            String city,
            String locationSummary,
            String locationVerificationStatus,
            String locationFailureReason,
            BigDecimal distanceMeters) {

        public StoreView(UUID id,String name,String city,String locationSummary,String locationVerificationStatus,String locationFailureReason) {
            this(id,name,city,locationSummary,locationVerificationStatus,locationFailureReason,null);
        }

        public StoreView(UUID id, String name, String city, String locationSummary) {
            this(id, name, city, locationSummary, "LEGACY", null);
        }
    }

    public record ResolveLocationRequest(
            String city,
            UUID salespersonId,
            LocationCommand location,
            String q) {

        public ResolveLocationRequest(String city, LocationCommand location, String q) {
            this(city, null, location, q);
        }

        public ResolveLocationRequest(String city, LocationCommand location) {
            this(city, null, location, null);
        }
    }

    public record SearchNewStoreRequest(
            UUID clientStoreId,
            String city,
            UUID salespersonId,
            LocationCommand location,
            String q,
            String locationVerificationToken) {

        public SearchNewStoreRequest(
                String city,
                UUID salespersonId,
                LocationCommand location,
                String q) {
            this(null, city, salespersonId, location, q, null);
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
            String nextAction,
            String selectionToken) {

        public NearbyStoreView(
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
                String nextAction) {
            this(source, storeId, poiId, name, city, address, distanceMeters, longitude, latitude,
                    locationSource, checkinEligible, nextAction, null);
        }
    }

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
            String poiLookupStatus,
            List<NearbyStoreView> nearbyStores,
            String locationVerificationToken,
            String manualEntryToken) {

        public LocationContextView(
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
                String poiLookupStatus,
                List<NearbyStoreView> nearbyStores) {
            this(geocodeStatus, address, formattedAddress, adcode, cityMatched, resolvedCity,
                    locationMessage, maxCheckinDistanceMeters, maxCheckinAccuracyMeters,
                    maxLocationAgeMinutes, accuracyAccepted, freshnessAccepted, poiLookupStatus,
                    nearbyStores, null, null);
        }
    }

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
            String privacyNoticeVersion,
            String locationVerificationToken,
            String locationFailureReason,
            UUID locationAttemptId) {

        public CreateSubmissionRequest(
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
                String privacyNoticeVersion,
                String locationVerificationToken) {
            this(clientSubmissionId, submissionKey, city, salespersonId, storeId, customerName,
                    customerPhone, visitResult, location, privacyAccepted, privacyNoticeVersion,
                    locationVerificationToken, null, null);
        }

        public CreateSubmissionRequest(
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
                String privacyNoticeVersion) {
            this(clientSubmissionId, submissionKey, city, salespersonId, storeId, customerName,
                    customerPhone, visitResult, location, privacyAccepted, privacyNoticeVersion,
                    null, null, null);
        }
    }

    public record DraftSubmissionView(UUID id, String status, Instant createdAt) { }

    public record MediaUploadView(
            UUID id,
            String kind,
            String status,
            String sha256,
            long sizeBytes,
            UUID segmentId,
            String originalFilename,
            UUID photoId) {

        public MediaUploadView(UUID id, String kind, String status, String sha256, long sizeBytes,
                UUID segmentId, String originalFilename) {
            this(id, kind, status, sha256, sizeBytes, segmentId, originalFilename, null);
        }

        public MediaUploadView(UUID id, String kind, String status, String sha256, long sizeBytes) {
            this(id, kind, status, sha256, sizeBytes, null, null);
        }
    }

    public record MediaDeleteView(UUID id, String kind, String status, UUID segmentId, UUID photoId) {

        public MediaDeleteView(UUID id, String kind, String status, UUID segmentId) {
            this(id, kind, status, segmentId, null);
        }

        public MediaDeleteView(UUID id, String kind, String status) {
            this(id, kind, status, null);
        }
    }

    public record CompletedSubmissionView(UUID id, String status, Instant submittedAt) { }

    public record ErrorResponse(String code, String message) { }
}
