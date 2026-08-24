package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;

/** ERP 销售出库确认命令；确认后生成销售出库单并扣减出库仓库存。 */
public record InternalSalesStockOutCommand(
        Long salesOrderId,
        String salesOrderNo,
        Long warehouseId,
        Long customerId,
        String customerNameSnapshot,
        Instant stockOutTime,
        List<InternalSalesStockOutLineCommand> lines,
        String remark) {
    public InternalSalesStockOutCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
