package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

public record AttendanceView(
        String month,
        LocalDate through,
        boolean provisional,
        AttendanceRuleView rule,
        String sourceError,
        HrPageView<AttendanceRow> page) {}
