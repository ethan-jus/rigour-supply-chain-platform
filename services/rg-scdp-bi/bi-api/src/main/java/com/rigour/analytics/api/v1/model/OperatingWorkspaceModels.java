package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 经营目标及运营跟进契约；跟进状态不代表订单、库存或回款状态。 */
public final class OperatingWorkspaceModels {
    private OperatingWorkspaceModels() {}

    public record ActionCommand(String kind, String businessRef, String businessLabel,
            String cityCode, String employeeCode, String assignee, Instant dueAt, String note, LocalDate promisedOn, Boolean disputed) {
        public ActionCommand(String kind,String ref,String label,String city,String employee,String assignee,Instant due,String note) {
            this(kind,ref,label,city,employee,assignee,due,note,null,null);
        }
    }
    public record ActionUpdateCommand(String assignee, Instant dueAt, String status,
            String note, Integer expectedRevision, LocalDate promisedOn, Boolean disputed) {
        public ActionUpdateCommand(String assignee,Instant due,String status,String note,Integer revision) {
            this(assignee,due,status,note,revision,null,null);
        }
    }
    public record ActionView(String id, String kind, String businessRef, String businessLabel,
            String cityCode, String employeeCode, String assignee, Instant dueAt, String status,
            String note, int revision, String createdBy, Instant createdAt, Instant updatedAt, LocalDate promisedOn, Boolean disputed) {}
    public record ActionPage(List<ActionView> items, long total, int page, int pageSize) {}
    public record ActionEventView(String id, String actionId, int revision,
            String previousStatus, String status, String previousAssignee, String assignee,
            Instant previousDueAt, Instant dueAt, String note, String actor, Instant occurredAt, LocalDate promisedOn, Boolean disputed) {}
}
