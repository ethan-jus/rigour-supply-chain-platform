package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
        Instant updatedTime,
        String supplierCode,
        String targetWarehouseCode,
        LocalDate purchaseDate,
        BigDecimal freightAmount,
        String freightBearerCode,
        String contractNo,
        String contractFileKey,
        String receiverName,
        String receiverPhone,
        String receivingAddress,
        String confirmationStatusCode) {
    public InternalProcurementOrderDetailView(
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
        this(
                id,
                procurementNo,
                sourceSystemCode,
                sourceDocumentNo,
                supplierId,
                supplierName,
                targetWarehouseId,
                targetWarehouseName,
                statusCode,
                expectedArrivalTime,
                totalQuantity,
                totalAmount,
                lines,
                remark,
                revision,
                createdBy,
                createdTime,
                updatedBy,
                updatedTime,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public InternalProcurementOrderDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
