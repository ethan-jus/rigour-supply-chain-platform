package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 入库单列表视图；列表只展示核心业务字段，明细数据进入详情页。 */
public record InternalStockInOrderSummaryView(
        Long id,
        String stockInNo,
        String sourceSystemCode,
        String sourceDocumentNo,
        String stockInTypeCode,
        Long procurementOrderId,
        String procurementNo,
        Long transferOrderId,
        String transferOrderNo,
        Long warehouseId,
        String warehouseName,
        Long supplierId,
        String supplierName,
        String statusCode,
        Instant stockInTime,
        BigDecimal totalQuantity,
        BigDecimal totalAmount,
        Integer lineCount,
        Integer revision,
        Instant updatedTime) {
}
