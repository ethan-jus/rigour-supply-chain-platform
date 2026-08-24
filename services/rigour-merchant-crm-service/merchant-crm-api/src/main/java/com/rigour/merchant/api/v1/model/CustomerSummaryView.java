package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerSummaryView(
        UUID id, String code, String name, String internalStatus, String account,
        String typeName, String areaName, String contactName, String phone,
        String staffName, List<SalesAssignmentView> salesAssignments,
        Instant sourceUpdatedAt, Instant syncedAt,
        /** PRESENT=已见；ABSENT_CANDIDATE=待下次完整快照确认；ABSENT/DELETED=来源已删除。 */
        String sourcePresence,
        String sourceStatus,
        /** 首次完整快照未见的时间，候选期间保留首次值。 */
        Instant sourceAbsentAt) {
    public CustomerSummaryView {
        salesAssignments = salesAssignments == null ? List.of() : List.copyOf(salesAssignments);
    }

    /** 兼容未识别来源缺失时间的旧调用方。 */
    public CustomerSummaryView(
            UUID id, String code, String name, String internalStatus, String account,
            String typeName, String areaName, String contactName, String phone,
            String staffName, List<SalesAssignmentView> salesAssignments,
            Instant sourceUpdatedAt, Instant syncedAt, String sourcePresence, String sourceStatus) {
        this(id, code, name, internalStatus, account, typeName, areaName, contactName, phone,
                staffName, salesAssignments, sourceUpdatedAt, syncedAt, sourcePresence,
                sourceStatus, null);
    }
}
