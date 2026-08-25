package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.SalespersonOption;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 受保护后台的城市范围、查询选项和拜访列表模型。 */
public final class TemporaryCheckinAdminModels {

    private TemporaryCheckinAdminModels() { }

    public record AdminScopeView(String username, boolean allCities, String city) { }

    public record AdminOptionsResponse(
            AdminScopeView scope,
            List<String> cities,
            List<SalespersonOption> salespersons,
            AdminMediaStorageStats mediaStats) { }

    public record AdminMediaStorageStats(
            long activeFiles,
            long totalBytes,
            long imageBytes,
            long audioBytes,
            Instant oldestCreatedAt) { }

    public record AdminSubmissionView(
            UUID id,
            String status,
            String city,
            UUID salespersonId,
            String salespersonName,
            UUID storeId,
            String storeName,
            Long visitOrdinal,
            String visitType,
            Long revisitNumber,
            String customerName,
            String customerPhone,
            String visitResult,
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal accuracyMeters,
            Instant locationCapturedAt,
            String locationNote,
            String locationAddress,
            String locationAdcode,
            String identityMethod,
            String submittedIpMasked,
            String userAgentSummary,
            String riskLevel,
            List<String> riskFlags,
            boolean storefrontPhotoAvailable,
            boolean wechatScreenshotAvailable,
            boolean audioAvailable,
            Instant storefrontPhotoDeletedAt,
            Instant wechatScreenshotDeletedAt,
            Instant audioDeletedAt,
            String transcriptionStatus,
            String transcript,
            String transcriptionErrorCode,
            String summaryStatus,
            String summary,
            String summaryErrorCode,
            Instant createdAt,
            Instant submittedAt) { }

    public record AdminSubmissionPage(
            AdminScopeView scope,
            List<AdminSubmissionView> items,
            long total,
            long totalElements,
            int page,
            int size,
            int totalPages) { }

    public record DeleteMediaRequest(String reason) { }

    public record DeleteMediaView(UUID id, String kind, String status, Instant deletedAt) { }

    public record TranscriptionView(UUID id, String transcriptionStatus, String summaryStatus) { }
}
