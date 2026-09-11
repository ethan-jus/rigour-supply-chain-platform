package com.rigour.hr.api.v1.model;

import java.util.List;

/** 外部员工批量同步命令；sourceSystem 使用 FEISHU、DINGHUOBAO 等稳定枚举值。 */
public record ExternalEmployeeSyncCommand(
        String sourceSystem,
        List<ExternalEmployeeRowCommand> rows) {
    public ExternalEmployeeSyncCommand {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
