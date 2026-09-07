package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 入库单详情视图；保留入库明细和来源单据关系，便于库存追溯。 */
public record InternalStockInOrderDetailView(
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
        List<InternalStockInOrderLineView> lines,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public InternalStockInOrderDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
