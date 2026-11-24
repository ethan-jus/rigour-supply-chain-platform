package com.rigour.sales.api.v1.model;

import java.time.*;
import java.util.*;

public record FieldworkReport(
        LocalDate date,
        Instant asOf,
        List<Employee> items,
        int total,
        Summary summary,
        List<City> cities,
        List<Option> departments,
        List<Option> people) {
    public record Option(String id, String name) {}

    public record City(String name, int people, int stores, int pendingPeople) {}

    public record Period(Instant start, Instant end) {}

    public record Punch(String type, Instant at, Instant received) {}

    public record Visit(
            UUID id,
            UUID profileId,
            LocalDate date,
            String employee,
            String name,
            String store,
            String customer,
            String status,
            Instant arrived,
            Instant departed,
            String result,
            String outcome,
            String intention,
            UUID planId,
            int sequence,
            String assessment,
            String reason,
            String ruleVersion,
            Integer minimumMinutes,
            int photos,
            int files,
            int verifiedFiles,
            long verifiedMillis,
            String objective,
            String city,
            String departmentName,
            Instant receivedAt,
            boolean hasStorefrontPhoto,
            Long playbackMillis,
            String communicationResult) {}

    public record VisitContent(boolean submitted, VisitForm form) {}
    public record VisitContact(String name, String role) {}
    public record VisitBusiness(String business, String intention, String progress) {}
    public record VisitForm(String businessType, String businessStatus, String statusReason,
            String outcome, boolean contacted, List<VisitContact> contacts, boolean noIntention,
            List<VisitBusiness> businesses, String noContactReason, String noContactDetail,
            String nextAction, LocalDate nextDate) {}

    public record Issue(UUID id, UUID visitId, String kind, String title, String status) {}

    public record Employee(
            UUID salesProfileId,
            String employeeCode,
            String employeeName,
            String city,
            String departmentName,
            UUID workDayId,
            String status,
            int punchCount,
            int storeCount,
            int visitCount,
            int effectiveVisits,
            int planned,
            int completedPlans,
            int workMinutes,
            int files,
            int verifiedFiles,
            long verifiedMillis,
            Integer distanceMeters,
            Integer gapCount,
            boolean summaryPending,
            Integer newContacts,
            String contactStatus,
            Instant contactsThrough,
            List<Instant> contactTimes,
            List<Period> periods,
            List<Punch> punches,
            List<Visit> visits,
            List<Issue> issues,
            int pendingReviewCount) {}

    public record Summary(
            int people,
            int punched,
            int punches,
            int stores,
            int visits,
            int planned,
            int completedPlans,
            long workMinutes,
            int active,
            int missingCheckout,
            int files,
            int verifiedFiles,
            long verifiedMillis,
            long distanceMeters,
            int withDistance,
            int contacts,
            int withContacts,
            int pendingPeople,
            int signedInPeople,
            int visitingPeople) {}

    public record Commitment(
            UUID id,
            UUID profileId,
            String employee,
            String salesperson,
            String store,
            LocalDate due,
            String status,
            UUID visitId,
            LocalDate visitDate,
            Instant updated,
            String storeId,
            String city) {}

    public record StoreVisitCounts(long storeVisits, long salespersonVisits) {}

    public record Ledger(
            List<Visit> items,
            int total,
            int stores,
            int completed,
            int pending,
            List<String> cities,
            List<Option> departments,
            List<Option> people,
            Map<String, Integer> assessmentCounts,
            Map<UUID, StoreVisitCounts> storeVisitCounts,
            Map<UUID, String> storeRegions) {}
}
