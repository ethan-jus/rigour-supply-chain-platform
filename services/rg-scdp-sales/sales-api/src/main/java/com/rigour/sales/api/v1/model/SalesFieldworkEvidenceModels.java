package com.rigour.sales.api.v1.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SalesFieldworkEvidenceModels {
    private SalesFieldworkEvidenceModels() {}
    public record DeviceEvent(UUID eventId, String eventType, Instant occurredAt, Instant receivedAt,
            String installationReference, Instant firstSeenAt, String evidenceStatus) {}
    public record DeviceEvidence(UUID workDayId, String proofStatus, String registrationStatus,
            String installationComparison, List<DeviceEvent> events) {}
    public record Capture(UUID visitId, UUID salesProfileId, UUID workDayId, LocalDate businessDate,
            Long crmStoreId, Long crmVisitId, String storeName, Instant checkedInAt, Instant checkedOutAt,
            String captureStatus, String resultNote, String contactOutcome, String intentionLevel) {}
    public record VisitHistory(boolean storeLinked, Capture current, long ownVisits7, long ownDays7,
            long ownVisits30, long ownDays30, long visibleVisits30, long visibleDays30,
            List<Capture> items, long total, int page, int pageSize) {}
    public record StartReview(String requestId, String kind, String note) {}
}
