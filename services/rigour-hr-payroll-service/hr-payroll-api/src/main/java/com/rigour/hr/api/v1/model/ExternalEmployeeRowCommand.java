package com.rigour.hr.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 外部来源员工行；来源字段完整保留，HR 负责生成我方 employeeCode。 */
public record ExternalEmployeeRowCommand(
        UUID connectorId,
        String sourceTenantKey,
        String sourceEmployeeId,
        String accountName,
        String employeeName,
        String jobCategory,
        String positionName,
        String departmentName,
        String leaderName,
        String regionName,
        String cityName,
        String mobile,
        String email,
        String employmentStatus,
        Instant entryDate,
        Instant leaveDate,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        String sourcePayloadHash,
        String sourcePayloadJson) {
}
