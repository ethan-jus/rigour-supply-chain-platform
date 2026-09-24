package com.rigour.hr.api.v1.model;
public record DhbBindingReviewView(long bindingId, long version, long employeeId,
        String employeeCode, String employeeName, String mobile, String departmentName,
        String sourceStaffId, String accountName, String sourceEmployeeName,
        String sourceMobile, String reason) {}
