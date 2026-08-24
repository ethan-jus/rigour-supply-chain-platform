package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;

/** ERP 采购入库确认命令；按采购订单明细入库，不直接接收订货宝字段。 */
public record InternalProcurementStockInCommand(
        Long procurementOrderId,
        Integer procurementRevision,
        Instant stockInTime,
        List<InternalProcurementStockInLineCommand> lines,
        String remark) {
    public InternalProcurementStockInCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
