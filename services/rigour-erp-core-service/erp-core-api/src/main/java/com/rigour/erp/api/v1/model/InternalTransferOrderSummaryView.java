package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 调拨单列表视图；列表只展示仓库、状态、数量和生成的出入库单号。 */
public record InternalTransferOrderSummaryView(
        Long id,
        String transferNo,
        String sourceSystemCode,
        String sourceDocumentNo,
        Long sourceWarehouseId,
        String sourceWarehouseName,
        Long targetWarehouseId,
        String targetWarehouseName,
        String statusCode,
        Instant stockOutTime,
        Instant stockInTime,
        Long stockOutOrderId,
        String stockOutNo,
        Long stockInOrderId,
        String stockInNo,
        BigDecimal totalQuantity,
        Integer lineCount,
        Integer revision,
        Instant updatedTime) {
}
