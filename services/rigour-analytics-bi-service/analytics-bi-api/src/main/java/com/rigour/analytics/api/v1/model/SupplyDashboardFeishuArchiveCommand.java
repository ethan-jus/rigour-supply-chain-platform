package com.rigour.analytics.api.v1.model;

import java.time.Instant;

/** 飞书旧看板归档登记命令；只登记元信息，不导入正式 BI 事实。 */
public record SupplyDashboardFeishuArchiveCommand(
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
        String remark) {
}
