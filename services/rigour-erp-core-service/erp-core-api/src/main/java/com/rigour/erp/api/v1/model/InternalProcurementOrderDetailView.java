package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 自研采购订单详情视图；外部来源字段用于区分同步投影和内部可操作单据。 */
public record InternalProcurementOrderDetailView(
        Long id,
        String procurementNo,
        String sourceSystemCode,
        String sourceDocumentNo,
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
