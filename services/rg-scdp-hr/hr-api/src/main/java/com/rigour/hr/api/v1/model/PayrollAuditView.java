package com.rigour.hr.api.v1.model;

import java.time.Instant;

public record PayrollAuditView(
        String action, String actorId, String reason, Instant occurredTime, String snapshotJson) {}
