package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 销售发货单列表视图。 */
public record SalesShipmentSummaryView(
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
}
