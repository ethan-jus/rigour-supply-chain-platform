package com.rigour.merchant.api.v1.model;

import java.util.List;

/** 外部客户/门店批量同步结果。 */
public record ExternalCrmCustomerSyncResult(
        int received,
        int created,
        int updated,
        int unchanged,
        int failed,
        List<ExternalCrmCustomerSyncRowResult> rows,
        List<String> failureMessages) {
    public ExternalCrmCustomerSyncResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        failureMessages = failureMessages == null ? List.of() : List.copyOf(failureMessages);
    }
}
