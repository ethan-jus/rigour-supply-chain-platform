package com.rigour.erp.api.v1.model;

public record ErpCostSourceView(
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
