package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 调拨单详情视图；包含调拨明细和确认后生成的出入库单信息。 */
public record InternalTransferOrderDetailView(
        Long id,
        String transferNo,
        String sourceSystemCode,
        String sourceDocumentNo,
        Long sourceWarehouseId,
        String sourceWarehouseName,
        Long targetWarehouseId,
        String targetWarehouseName,
        String outboundOperatorStaffCode,
        String outboundOperatorStaffNameSnapshot,
        String inboundOperatorStaffCode,
        String inboundOperatorStaffNameSnapshot,
        String statusCode,
        Instant stockOutTime,
        Instant stockInTime,
        Long stockOutOrderId,
        String stockOutNo,
        Long stockInOrderId,
        String stockInNo,
        BigDecimal totalQuantity,
        List<InternalTransferOrderLineView> lines,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public InternalTransferOrderDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
