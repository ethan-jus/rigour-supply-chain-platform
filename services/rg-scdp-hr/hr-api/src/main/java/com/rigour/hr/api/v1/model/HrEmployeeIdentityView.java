package com.rigour.hr.api.v1.model;

import java.util.List;

/** 员工身份最小投影；供账号关联及订单归属核验，不包含薪资或联系方式。 */
public record HrEmployeeIdentityView(
        Long id,
        String employeeCode,
        String employeeName,
        String employmentStatus,
        Long departmentId,
        String departmentName,
        String positionCode,
        String positionName,
        List<Long> departmentAncestorIds,
        int employeeRevision,
        long organizationVersion,
        long accessVersion,
        boolean usable,
        String unavailableReason) {
    public HrEmployeeIdentityView {
        departmentAncestorIds = List.copyOf(departmentAncestorIds);
    }
}
