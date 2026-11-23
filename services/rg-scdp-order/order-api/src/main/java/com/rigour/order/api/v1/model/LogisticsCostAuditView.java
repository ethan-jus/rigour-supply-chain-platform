package com.rigour.order.api.v1.model;

import java.time.Instant;

public record LogisticsCostAuditView(
        String action,
        String actorId,
        String reason,
        Instant occurredTime,
        LogisticsCostDetailView snapshot,
        java.util.Map<String, Object> evidence) {
    public static final String SHIPMENT_LOGISTICS_EDIT = "SHIPMENT_LOGISTICS_EDIT";

    public LogisticsCostAuditView(
            String action,
            String actorId,
            String reason,
            Instant occurredTime,
            LogisticsCostDetailView snapshot) {
        this(action, actorId, reason, occurredTime, snapshot, null);
    }
}
