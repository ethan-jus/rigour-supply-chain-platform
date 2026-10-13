package com.rigour.order.api.v1.model;

import java.time.Instant;

public record LogisticsCostAuditView(
        String action,
        String actorId,
        String reason,
        Instant occurredTime,
        LogisticsCostDetailView snapshot) {}
