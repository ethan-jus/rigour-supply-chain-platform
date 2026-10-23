package com.rigour.hr.api.v1.model;

import java.util.List;

public record AttendanceRuleListView(
        String month,
        Integer companyExpectedDays,
        List<Long> configuredDepartmentIds,
        List<Long> lockedDepartmentIds,
        HrPageView<AttendanceRuleListRow> page) {}
