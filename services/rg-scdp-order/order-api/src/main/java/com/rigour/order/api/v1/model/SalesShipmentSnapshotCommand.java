package com.rigour.order.api.v1.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 来源发货事实先留存；不要求先完成原单、仓库及商品映射，也不触发库存。 */
public record SalesShipmentSnapshotCommand(
        UUID connectorId,
        String shipmentNo,
        String sourceOrderNo,
        String customerCode,
        String customerName,
        String contactPhone,
        String warehouseName,
        String statusCode,
        String logisticsCompany,
        String trackingNo,
        Instant shipTime,
        Instant stockOutTime,
        Instant sourceUpdatedAt,
        Map<String, Object> payload) {
    public SalesShipmentSnapshotCommand {
        payload =
                payload == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(
                                new java.util.LinkedHashMap<>(payload));
    }
}
