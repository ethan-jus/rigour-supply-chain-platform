package com.rigour.sales.api.v1.model;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
public final class SalesFieldworkModels {
    private SalesFieldworkModels() {}
    public record Department(String id,String name) {}
    public record PersonDay(UUID salesProfileId, String employeeCode, String employeeName,
            String departmentName, UUID workDayId, String status, Instant checkedInAt,
            Instant checkedOutAt, int workMinutes, Integer distanceMeters, Integer gapCount,
            int visitCount, int pendingReviewCount, boolean summaryPending) {}
    public record Totals(long total, long active, long notStarted, long finished, long locationAnomaly, long pendingReview) {}
    public record Page(LocalDate date, List<PersonDay> items, long total, int page, int pageSize, Totals totals) {
        public Page { items = List.copyOf(items); }
    }
}
