package com.rigour.hr.api.v1.model;

import java.time.Instant;

/** 外部来源人员解析到的 HR 员工主档摘要。 */
public record ExternalEmployeeResolvedView(String sourceSystem,
                                           String sourceTenantKey,
                                           String sourceEmployeeId,
                                           Long employeeId,
                                           String employeeCode,
                                           String employeeName,
                                           String employmentStatus,
                                           String jobCategory,
                                           String positionCode,
                                           String positionName,
                                           String departmentName,
                                           String leaderEmployeeCode,
                                           String leaderName,
                                           String regionName,
                                           String cityName,
                                           Instant sourceUpdatedAt) {
}
