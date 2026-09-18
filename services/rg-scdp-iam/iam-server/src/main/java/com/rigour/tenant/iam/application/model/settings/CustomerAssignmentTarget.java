package com.rigour.tenant.iam.application.model.settings;

import java.util.UUID;

public record CustomerAssignmentTarget(
        UUID tenantId,
        UUID userId,
        String employeeCode,
        String employeeName,
        String memberStatus,
        boolean usable,
        String unavailableReason,
        long authorizationVersion,
        AppAuthorizationSnapshot.Limit regionLimit) {}
