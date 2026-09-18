package com.rigour.hr.api.v1.model;

import java.time.Instant;

/** 人工维护员工及主部门/岗位，不维护登录密码或角色。 */
public record HrEmployeeCommand(
        String employeeName,
        Long departmentId,
        String positionCode,
        String employmentStatus,
        String mobile,
        String email,
        Instant entryDate,
        Instant leaveDate,
        String remark,
        int revision, HrEmployeeProfile profile, String jobGrade) {}
