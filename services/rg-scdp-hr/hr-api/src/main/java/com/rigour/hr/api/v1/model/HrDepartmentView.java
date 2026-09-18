package com.rigour.hr.api.v1.model;

import java.time.Instant;
import java.time.LocalDate;

/** 部门树与资料；审计字段只读，姓名为操作时的显示快照。 */
public record HrDepartmentView(
        Long id, Long parentId, String departmentCode, String departmentName,
        int sortOrder, String statusCode, int revision,
        String leaderName, String contactPhone, LocalDate establishedDate,
        Instant createdTime, String createdBy, String createdByName,
        Instant updatedTime, String updatedBy, String updatedByName, String leaderEmployeeCode) {}
