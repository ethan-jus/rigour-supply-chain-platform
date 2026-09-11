package com.rigour.hr.api.v1.model;

import java.util.List;

/** 外部员工同步结果；按行返回目标 employeeCode，便于 Integration 回写投影状态。 */
public record ExternalEmployeeSyncResult(
        int received,
        int created,
        int updated,
        int unchanged,
        int failed,
        List<ExternalEmployeeSyncRowResult> rows,
        List<String> failureMessages) {
    public ExternalEmployeeSyncResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        failureMessages = failureMessages == null ? List.of() : List.copyOf(failureMessages);
    }
}
