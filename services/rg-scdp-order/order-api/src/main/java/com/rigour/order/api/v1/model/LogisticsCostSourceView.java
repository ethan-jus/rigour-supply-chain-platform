package com.rigour.order.api.v1.model;

public record LogisticsCostSourceView(
        Long id,
        String documentNo,
        String sourceType,
        String cityCode,
        Long warehouseId,
        Long targetWarehouseId,
        String senderCode,
        String senderName,
        String receiverCode,
        String receiverName,
        String statusCode) {}
