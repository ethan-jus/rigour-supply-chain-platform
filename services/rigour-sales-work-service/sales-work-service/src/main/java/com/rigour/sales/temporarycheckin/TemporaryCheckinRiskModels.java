package com.rigour.sales.temporarycheckin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 后台关联线索契约；浏览器标识不等于硬件，文件一致不等于违规，复核独立于原拜访结论。 */
public final class TemporaryCheckinRiskModels {
    private TemporaryCheckinRiskModels() { }
    public static final String RULES_VERSION = "DEVICE_AUDIO_V1";

    public record Page<T>(List<T> items,int page,int size,long totalElements,int totalPages) { }
    public record GroupSummary(String id,String code,String kind,long historyCount,long filterCount,
            long salespersonCount,long storeCount,long dateCount,Instant firstSubmittedAt,Instant lastSubmittedAt,
            String firstSalespersonName,String reviewStatus,String evidenceVersion,boolean newEvidence,
            Instant reviewedAt,String reviewedBy,String shaPrefix,Long sizeBytes,Long durationMs,String durationSource,
            Long otherCount,Long earlierCount,List<String> segmentIds) { }
    public record SubmissionSummary(UUID submissionId,GroupSummary device,List<GroupSummary> audios,
            List<String> riskReasons,String riskLevel,boolean reviewPending) { }
    public record BatchSummary(List<SubmissionSummary> items,String rulesVersion) { }
    public record SalespersonSummary(UUID salespersonId,String salespersonName,long count,
            Instant firstSubmittedAt,Instant lastSubmittedAt) { }
    public record AudioReference(String id,String code,String segmentId,String originalFilename,
            Long parsedDurationMs,Long clientDurationMs,Long durationMs,String durationSource,
            String originalUrl,String playbackUrl) { }
    public record Visit(UUID submissionId,Instant submittedAt,String city,UUID salespersonId,
            String salespersonName,UUID storeId,String storeName,String locationAddress,String deviceId,
            String deviceCode,List<AudioReference> audios,boolean matchesFilter) { }
    public record GroupDetail(GroupSummary summary,List<SalespersonSummary> salespeople,Page<Visit> visits,
            List<AssignmentEvent> assignments,List<ReviewEvent> reviews,String sha256,Long sizeBytes,
            Instant firstReceivedAt,String firstReceivedSource,List<String> browserSummaries,
            long assignmentsTotal,long reviewsTotal) { }
    public record AssignmentRequest(UUID clientEventId,String assignmentType,UUID salespersonId,
            LocalDate validFrom,LocalDate validTo,String note) { }
    public record AssignmentEvent(UUID id,UUID clientEventId,String assignmentType,UUID salespersonId,
            String salespersonName,LocalDate validFrom,LocalDate validTo,String note,String actor,
            Instant assignedAt,String scopeCity) { }
    public record ReviewRequest(UUID clientEventId,String evidenceVersion,String status,String note) { }
    public record ReviewEvent(UUID id,UUID clientEventId,String status,String note,String actor,
            Instant reviewedAt,String evidenceVersion,String rulesVersion,long memberCount,String scopeCity) { }
    public record IdentityEvent(String id,Instant occurredAt,UUID salespersonId,String salespersonName,String city,
            String eventType,String previousEventId,UUID previousSalespersonId,String previousSalespersonName,Instant previousOccurredAt) { }
    public record IdentityEventPage(List<IdentityEvent> items,int page,int size,long totalElements,int totalPages,
            long availableEventCount,Instant firstAvailableEventAt,boolean completeHistory) { }
    record Filters(Instant from,Instant until,String city,UUID salespersonId) { }
}
