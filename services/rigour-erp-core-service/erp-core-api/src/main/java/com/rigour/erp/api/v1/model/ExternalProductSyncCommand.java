package com.rigour.erp.api.v1.model;

import java.util.List;

/** 外部商品批量同步命令。 */
public record ExternalProductSyncCommand(
        String sourceSystem,
        List<ExternalProductRowCommand> rows) {
    public ExternalProductSyncCommand {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
