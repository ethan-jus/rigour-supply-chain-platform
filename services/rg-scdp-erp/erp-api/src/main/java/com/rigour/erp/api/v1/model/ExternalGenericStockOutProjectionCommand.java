package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 外部来源通用出库投影命令；用于不反推销售订单或调拨单的出库类型。 */
public record ExternalGenericStockOutProjectionCommand(
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
        String stockOutTypeCode,
        Long warehouseId,
        Instant stockOutTime,
        List<ExternalGenericStockOutProjectionLineCommand> lines,
        Boolean affectStockBalance,
        String remark) {
    public ExternalGenericStockOutProjectionCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean shouldAffectStockBalance() {
        return !Boolean.FALSE.equals(affectStockBalance);
    }
}
