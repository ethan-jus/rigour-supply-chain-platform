package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

/** 部门维护命令；租户及审计人员、时间仅由服务端生成。 */
public record HrDepartmentCommand(
        Long parentId, String departmentName, int sortOrder, String statusCode, int revision,
        String leaderEmployeeCode, String contactPhone, LocalDate establishedDate) {}
