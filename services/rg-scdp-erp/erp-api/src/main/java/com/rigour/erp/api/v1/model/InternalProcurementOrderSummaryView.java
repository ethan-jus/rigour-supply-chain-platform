package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** ERP 自研采购订单列表视图。 */
public record InternalProcurementOrderSummaryView(
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
        Integer lineCount,
        Integer revision,
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
        String confirmationStatusCode,
        Map<String, BigDecimal> quantityByUnit,
        String createdBy) {
    public InternalProcurementOrderSummaryView {
        quantityByUnit = quantityByUnit == null ? Map.of() : Map.copyOf(quantityByUnit);
    }
    public InternalProcurementOrderSummaryView(
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
            Integer lineCount,
            Integer revision,
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
                lineCount,
                revision,
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
                null,
                Map.of(),
                null);
    }
}
