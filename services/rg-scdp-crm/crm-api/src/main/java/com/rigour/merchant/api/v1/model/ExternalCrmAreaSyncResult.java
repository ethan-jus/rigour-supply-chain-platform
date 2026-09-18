package com.rigour.merchant.api.v1.model;

import java.util.List;

/** 外部区域/城市同步结果。 */
public record ExternalCrmAreaSyncResult(
        int received,
        int created,
        int updated,
        int unchanged,
        int failed,
        List<ExternalCrmAreaSyncRowResult> rows,
        List<String> failureMessages) {
    public ExternalCrmAreaSyncResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        failureMessages = failureMessages == null ? List.of() : List.copyOf(failureMessages);
    }
}
