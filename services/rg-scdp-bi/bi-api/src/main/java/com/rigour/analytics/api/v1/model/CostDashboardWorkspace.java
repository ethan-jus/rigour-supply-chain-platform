package com.rigour.analytics.api.v1.model;

/** 同一次在线授权、同一完整快照下的汇总与分页明细。 */
public record CostDashboardWorkspace(
        CostDashboardReport report, CostDashboardRecords records) {}
