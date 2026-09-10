package com.rigour.merchant.api.v1.model;

import java.util.List;

/** 外部客户/门店批量同步命令。 */
public record ExternalCrmCustomerSyncCommand(
        String sourceSystem,
        List<ExternalCrmCustomerRowCommand> rows) {
    public ExternalCrmCustomerSyncCommand {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
