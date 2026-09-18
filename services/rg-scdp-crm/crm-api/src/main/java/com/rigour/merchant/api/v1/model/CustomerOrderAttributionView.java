package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.List;

/** CRM/HR 当前归属的可核验快照，只用于订单创建时冻结，不代表历史订单的当前权限。 */
public record CustomerOrderAttributionView(
        String tenantId,
        long customerId,
        String customerCode,
        String customerName,
        String employeeCode,
        String employeeName,
        Long departmentId,
        String departmentName,
        List<Long> departmentAncestorIds,
        String regionCode,
        List<String> regionAncestorCodes,
        String sourceVersion,
        long customerRevision,
        long employeeRevision,
        long organizationVersion,
        Instant resolvedAt,
        boolean usable,
        String unavailableReason) {
    public CustomerOrderAttributionView {
        departmentAncestorIds = List.copyOf(departmentAncestorIds);
        regionAncestorCodes = List.copyOf(regionAncestorCodes);
    }
}
