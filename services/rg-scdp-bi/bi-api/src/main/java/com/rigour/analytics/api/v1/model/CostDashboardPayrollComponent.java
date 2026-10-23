package com.rigour.analytics.api.v1.model;

/** 工资分项以确认快照为准；有缺失时整项金额为空，不能把未知当作零。 */
public record CostDashboardPayrollComponent(
        String key,
        String label,
        String amount,
        String previous,
        String delta,
        long records,
        long missingCount) {}
