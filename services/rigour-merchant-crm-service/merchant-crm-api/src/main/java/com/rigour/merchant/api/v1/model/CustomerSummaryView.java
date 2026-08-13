package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerSummaryView(
        UUID id, String code, String name, String internalStatus, String account,
        String typeName, String areaName, String contactName, String phone,
        String staffName, List<SalesAssignmentView> salesAssignments,
        Instant sourceUpdatedAt, Instant syncedAt,
        String sourcePresence, String sourceStatus) {
    public CustomerSummaryView {
        salesAssignments = salesAssignments == null ? List.of() : List.copyOf(salesAssignments);
    }
}
