package com.rigour.analytics.api.v1.model;

import java.time.Instant;

/** 飞书旧看板归档登记结果。 */
public record SupplyDashboardFeishuArchiveView(
        Long id,
        String archiveCode,
        String tableId,
        String viewId,
        String tableName,
        String fileName,
        String fileFormat,
        String exportedBy,
        Instant exportedTime,
        Instant frozenTime,
        Long recordCount,
        String checksumSha256,
        String storageUri,
        String fieldMappingUri,
        String reconciliationReportUri,
        String archiveStatusCode,
        String remark,
        Instant createdTime,
        Instant updatedTime) {
}
