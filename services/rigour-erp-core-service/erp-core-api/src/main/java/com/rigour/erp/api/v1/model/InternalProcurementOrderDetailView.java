package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 自研采购订单详情视图；不暴露订货宝来源字段。 */
public record InternalProcurementOrderDetailView(
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
        List<InternalProcurementOrderLineView> lines,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public InternalProcurementOrderDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
