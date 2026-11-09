package com.rigour.analytics.api.v1.model;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardQuery(
        Integer year,
        Integer month,
        String compare,
        String city,
        String scope,
        String category,
        String dimension,
        String dimensionKey,
        String status,
        String payment,
        String search,
        String periodSide,
        Integer page,
        Integer size,
        String snapshotId,
        Boolean missingAmount,
        String department) {
    public CostDashboardQuery(
            Integer year,
            Integer month,
            String compare,
            String city,
            String scope,
            String category,
            String dimension,
            String dimensionKey,
            String status,
            String payment,
            String search,
            String periodSide,
            Integer page,
            Integer size,
            String snapshotId,
            Boolean missingAmount) {
        this(
                year,
                month,
                compare,
                city,
                scope,
                category,
                dimension,
                dimensionKey,
                status,
                payment,
                search,
                periodSide,
                page,
                size,
                snapshotId,
                missingAmount,
                null);
    }
}
