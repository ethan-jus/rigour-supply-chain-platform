package com.rigour.analytics.api.v1.model;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardExportView(
        String id, String status, String errorCode, Long rowCount, String downloadPath) {}
