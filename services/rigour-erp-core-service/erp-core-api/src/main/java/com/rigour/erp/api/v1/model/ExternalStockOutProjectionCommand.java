package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 外部来源出库投影命令；用于订货宝等来源生成 ERP 统一出库单。 */
public record ExternalStockOutProjectionCommand(
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
        String stockOutTypeCode,
        Long warehouseId,
        Long salesOrderId,
        String salesOrderNo,
        Long transferOrderId,
        String transferOrderNo,
        Long customerId,
        String customerNameSnapshot,
        Instant stockOutTime,
        List<ExternalStockOutProjectionLineCommand> lines,
        Boolean affectStockBalance,
        String remark) {
    public ExternalStockOutProjectionCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean shouldAffectStockBalance() {
        return !Boolean.FALSE.equals(affectStockBalance);
    }
}
