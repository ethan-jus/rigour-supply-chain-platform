package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 出库单列表视图；列表只展示来源、仓库、客户、状态和数量汇总。 */
public record InternalStockOutOrderSummaryView(
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
        Integer revision,
        Instant updatedTime) {
}
