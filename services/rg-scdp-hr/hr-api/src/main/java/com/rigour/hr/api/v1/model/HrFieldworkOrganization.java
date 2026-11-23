package com.rigour.hr.api.v1.model;

import java.util.Map;

public record HrFieldworkOrganization(
        String employeeCode,
        String employeeName,
        String city,
        Long departmentId,
        String departmentName,
        Map<Long, String> departmentAncestors) {}
