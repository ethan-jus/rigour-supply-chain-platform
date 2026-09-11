package com.rigour.erp.api.v1.model;

import java.util.List;

/** 外部商品批量同步结果。 */
public record ExternalProductSyncResult(
        int received,
        int created,
        int updated,
        int unchanged,
        int failed,
        List<ExternalProductSyncRowResult> rows,
        List<String> failureMessages) {
    public ExternalProductSyncResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        failureMessages = failureMessages == null ? List.of() : List.copyOf(failureMessages);
    }
}
