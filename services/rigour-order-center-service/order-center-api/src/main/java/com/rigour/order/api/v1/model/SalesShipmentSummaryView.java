package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 销售发货单列表视图。 */
public record SalesShipmentSummaryView(
        Long id,
        String shipmentNo,
        UUID connectorId,
        String sourceSystemCode,
        String sourceDocumentNo,
        Long salesOrderId,
        String salesOrderNoSnapshot,
        Long customerId,
        String customerCodeSnapshot,
        String customerNameSnapshot,
        String contactPhoneSnapshot,
        String regionCode,
        String ownerStaffCode,
        Long warehouseId,
        Long stockOutOrderId,
        String stockOutNo,
        String shipmentStatusCode,
        String logisticsCompany,
        String trackingNo,
        Instant shipTime,
        BigDecimal totalQuantity,
        Integer revision,
        Instant updatedTime) {
    public SalesShipmentSummaryView(
            Long id,
            String shipmentNo,
            Long salesOrderId,
            String salesOrderNoSnapshot,
            Long customerId,
            String customerCodeSnapshot,
            String customerNameSnapshot,
            String contactPhoneSnapshot,
            String regionCode,
            String ownerStaffCode,
            Long warehouseId,
            Long stockOutOrderId,
            String stockOutNo,
            String shipmentStatusCode,
            String logisticsCompany,
            String trackingNo,
            Instant shipTime,
            BigDecimal totalQuantity,
            Integer revision,
            Instant updatedTime) {
        this(id, shipmentNo, null, null, null, salesOrderId, salesOrderNoSnapshot,
                customerId, customerCodeSnapshot, customerNameSnapshot, contactPhoneSnapshot,
                regionCode, ownerStaffCode, warehouseId, stockOutOrderId, stockOutNo,
                shipmentStatusCode, logisticsCompany, trackingNo, shipTime, totalQuantity,
                revision, updatedTime);
    }
}
