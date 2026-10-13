package com.rigour.erp.api.v1.model;

import java.time.Instant;

public record ErpCostAuditView(
        String action,
        String actorId,
        String reason,
        Instant occurredTime,
        ErpCostDetailView snapshot) {}
