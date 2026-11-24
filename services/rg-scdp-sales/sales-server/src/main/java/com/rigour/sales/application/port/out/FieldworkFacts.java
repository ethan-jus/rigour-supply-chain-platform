package com.rigour.sales.application.port.out;

import com.rigour.sales.api.v1.model.FieldworkReport.*;

import java.time.*;
import java.util.*;

public interface FieldworkFacts {
    record Profile(
            UUID id,
            String code,
            String number,
            String hrId,
            UUID day,
            String status,
            Instant cutoff,
            Integer distance,
            Integer gaps,
            boolean pending) {}

    record VisitFact(Visit visit, String localStoreKey, Long crmVisitId, Long crmStoreId) {
        public String storeKey() { return crmStoreId == null ? localStoreKey : "C" + crmStoreId; }
    }

    record StaffPunch(UUID profile, Punch punch) {}

    record StaffPlan(UUID profile, UUID id) {}

    record StaffIssue(UUID profile, Issue issue) {}

    default List<Commitment> commitments(
            UUID tenant, SalesManagementDirectory.Access scope, String status, int limit, int offset) {
        return commitments(tenant, scope, status, null, null, null, limit, offset);
    }

    List<Commitment> commitments(
            UUID tenant,
            SalesManagementDirectory.Access scope,
            String status,
            LocalDate from,
            LocalDate through,
            String ownerCode,
            int limit,
            int offset);

    List<Profile> roster(UUID tenant, LocalDate date, SalesManagementDirectory.Access scope);

    List<StaffPunch> punches(UUID tenant, LocalDate date, SalesManagementDirectory.Access scope);

    List<StaffPlan> plans(UUID tenant, LocalDate date, SalesManagementDirectory.Access scope);

    List<StaffIssue> issues(UUID tenant, LocalDate date, SalesManagementDirectory.Access scope);

    Map<UUID, StoreVisitCounts> storeVisitCounts(
            UUID tenant, SalesManagementDirectory.Access scope, List<UUID> visitIds);

    Optional<VisitContent> visitContent(UUID tenant, SalesManagementDirectory.Access scope, UUID visitId);

    List<VisitFact> visits(
            UUID tenant,
            LocalDate from,
            LocalDate to,
            SalesManagementDirectory.Access scope,
            UUID profile);
}
