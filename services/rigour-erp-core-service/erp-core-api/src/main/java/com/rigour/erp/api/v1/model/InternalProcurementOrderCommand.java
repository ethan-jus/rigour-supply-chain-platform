package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;

/** ERP 采购订单保存/提交命令；采购单号由后端统一生成。 */
public record InternalProcurementOrderCommand(
        Boolean submit,
        Long supplierId,
        Long targetWarehouseId,
        Instant expectedArrivalTime,
        List<InternalProcurementOrderLineCommand> lines,
        String remark,
        Integer revision) {
    public InternalProcurementOrderCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
