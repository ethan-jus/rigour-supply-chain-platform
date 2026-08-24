package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;

/** 外部来源调拨出库投影命令；用于订货宝只提供调拨出库、未提供调拨单接口的场景。 */
public record ExternalTransferStockOutProjectionCommand(
        String sourceSystemCode,
        String sourceDocumentNo,
        Long sourceWarehouseId,
        Long targetWarehouseId,
        Instant stockOutTime,
        List<ExternalTransferStockOutProjectionLineCommand> lines,
        String remark) {
    public ExternalTransferStockOutProjectionCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
