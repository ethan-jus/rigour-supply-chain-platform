package com.rigour.sales.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SalesFieldworkReviewModels {
    private SalesFieldworkReviewModels() {}
    public record Case(UUID id, UUID salesProfileId, UUID workDayId, UUID visitId, String kind,
                       String title, String evidenceJson, String status, long version,
                       Instant detectedAt, Instant updatedAt, List<Event> events) {}
    public record Event(UUID id, String type, String decision, String note, boolean employeeStatement, Instant createdAt) {}
    public record Statement(String requestId, String note) {}
    public record Review(String requestId, long expectedVersion, String decision, String note) {}
}
