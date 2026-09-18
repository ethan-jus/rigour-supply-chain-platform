package com.rigour.tenant.iam.api.v1.model;

import java.util.UUID;

/** 主责分配只读目标事实；地区上限不代表客户主责，不授予调用者访问权。 */
public record CustomerAssignmentTargetView(
        UUID tenantId,
        UUID userId,
        String employeeCode,
        String employeeName,
        String memberStatus,
        boolean usable,
        String unavailableReason,
        long authorizationVersion,
        SupplyAuthorizationView.Limit regionLimit) {}
