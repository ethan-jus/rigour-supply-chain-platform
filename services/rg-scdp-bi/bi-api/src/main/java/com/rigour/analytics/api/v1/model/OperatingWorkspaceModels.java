package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 经营目标及运营跟进契约；跟进状态不代表订单、库存或回款状态。 */
public final class OperatingWorkspaceModels {
    private OperatingWorkspaceModels() {}

    public record TargetCommand(String month, String dimensionType, String dimensionCode,
            String dimensionName, String metricCode, BigDecimal targetValue, String remark,
            Integer expectedRevision) {}
    public record TargetView(String id, String month, String dimensionType, String dimensionCode,
            String dimensionName, String metricCode, BigDecimal targetValue, String remark,
            int revision, Instant updatedAt) {}
    public record ActionCommand(String kind, String businessRef, String businessLabel,
            String cityCode, String employeeCode, String assignee, Instant dueAt, String note) {}
    public record ActionUpdateCommand(String assignee, Instant dueAt, String status,
            String note, Integer expectedRevision) {}
    public record ActionView(String id, String kind, String businessRef, String businessLabel,
            String cityCode, String employeeCode, String assignee, Instant dueAt, String status,
            String note, int revision, String createdBy, Instant createdAt, Instant updatedAt) {}
    public record ActionPage(List<ActionView> items, long total, int page, int pageSize) {}
    public record ActionEventView(String id, String actionId, int revision,
            String previousStatus, String status, String previousAssignee, String assignee,
            Instant previousDueAt, Instant dueAt, String note, String actor, Instant occurredAt) {}
}
