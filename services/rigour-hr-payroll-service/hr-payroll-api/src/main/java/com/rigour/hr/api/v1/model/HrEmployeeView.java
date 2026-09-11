package com.rigour.hr.api.v1.model;

import java.time.Instant;

/** HR 员工主档视图；用于页面展示和跨域按 employeeCode 建立关系。 */
public record HrEmployeeView(
        Long id,
        String employeeCode,
        String employeeName,
        String mobile,
        String email,
        String employmentStatus,
        String jobCategory,
        String positionCode,
        String positionName,
        String departmentName,
        String leaderEmployeeCode,
        String leaderName,
        String regionName,
        String cityName,
        String sourceSystem,
        String sourceDocumentNo,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        Instant entryDate,
        Instant leaveDate,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
}
