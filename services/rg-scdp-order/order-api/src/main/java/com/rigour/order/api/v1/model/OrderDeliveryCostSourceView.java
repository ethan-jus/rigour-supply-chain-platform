package com.rigour.order.api.v1.model;

public record OrderDeliveryCostSourceView(
        Long id,
        String documentNo,
        String cityCode,
        Long warehouseId,
        String customerCode,
        String customerName,
        String statusCode) {}
