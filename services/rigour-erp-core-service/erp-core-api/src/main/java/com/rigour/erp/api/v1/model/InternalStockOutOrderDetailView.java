package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 出库单详情视图；详情展示单据头、库存扣减明细和来源销售/调拨快照。 */
public record InternalStockOutOrderDetailView(
        Long id,
        String stockOutNo,
        String stockOutTypeCode,
        Long warehouseId,
        String warehouseName,
        Long salesOrderId,
        String salesOrderNo,
        Long transferOrderId,
        String transferOrderNo,
        Long customerId,
        String customerNameSnapshot,
        String statusCode,
        Instant stockOutTime,
        BigDecimal totalQuantity,
        Integer lineCount,
        List<InternalStockOutOrderLineView> lines,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public InternalStockOutOrderDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
