package com.rigour.order.api.v1.model;

import java.util.List;
import java.util.Map;

/** 经证据复核的历史归属；未知历史组织可留空，不能按今天的部门推造。 */
public final class OrderAttributionReview {
    private OrderAttributionReview() {}

    public record Snapshot(
            String employeeCode,
            String employeeName,
            Long departmentId,
            String departmentName,
            List<Long> departmentPath,
            String regionCode,
            List<String> regionPath) {}

    public record Propose(
            Integer orderRevision,
            Long snapshotRevision,
            Snapshot proposed,
            String evidenceRef,
            String evidenceText,
            String reason) {}

    public record Review(boolean approve, String reason) {}

    public record Context(
            long orderId,
            String orderNo,
            String sourceSystemCode,
            String sourceOrderNo,
            int orderRevision,
            long snapshotRevision,
            Map<String, Object> current,
            List<Adjustment> adjustments) {}

    public record Adjustment(
            String id,
            String status,
            int expectedOrderRevision,
            long expectedSnapshotRevision,
            Map<String, Object> before,
            Snapshot proposed,
            String evidenceRef,
            String evidenceText,
            String reason,
            String proposedBy,
            String proposedAt,
            String reviewedBy,
            String reviewedAt,
            String reviewReason) {}
}
