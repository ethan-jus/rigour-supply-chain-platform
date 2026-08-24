package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 自研采购订单列表视图。 */
public record InternalProcurementOrderSummaryView(
        Long id,
        String procurementNo,
        Long supplierId,
        String supplierName,
        Long targetWarehouseId,
        String targetWarehouseName,
        String statusCode,
        Instant expectedArrivalTime,
        BigDecimal totalQuantity,
        BigDecimal totalAmount,
        Integer lineCount,
        Integer revision,
        Instant updatedTime) {
}
