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
        int revision, HrEmployeeProfile profile, String jobGrade, Boolean regularized, java.time.LocalDate regularizationDate,
        String payrollCategory, java.time.LocalDate salaryEffectiveFrom,
        java.util.List<HrEmployeeCityScopeCommand> cityScopes) {
    public HrEmployeeCommand(String employeeName, Long departmentId, String positionCode,
            String employmentStatus, String mobile, String email, Instant entryDate, Instant leaveDate,
            String remark, int revision, HrEmployeeProfile profile, String jobGrade, Boolean regularized,
            java.time.LocalDate regularizationDate, String payrollCategory, java.time.LocalDate salaryEffectiveFrom) {
        this(employeeName, departmentId, positionCode, employmentStatus, mobile, email, entryDate,
                leaveDate, remark, revision, profile, jobGrade, regularized, regularizationDate,
                payrollCategory, salaryEffectiveFrom, null);
    }
    public HrEmployeeCommand(String employeeName, Long departmentId, String positionCode,
            String employmentStatus, String mobile, String email, Instant entryDate, Instant leaveDate,
            String remark, int revision, HrEmployeeProfile profile, String jobGrade) {
        this(employeeName, departmentId, positionCode, employmentStatus, mobile, email, entryDate,
                leaveDate, remark, revision, profile, jobGrade, null, null, null, null);
    }
}
