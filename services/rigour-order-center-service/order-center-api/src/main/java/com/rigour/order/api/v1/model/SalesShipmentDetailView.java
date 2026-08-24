package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售发货单详情视图。 */
public record SalesShipmentDetailView(
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
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime,
        List<SalesShipmentLineView> lines) {
    public SalesShipmentDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
