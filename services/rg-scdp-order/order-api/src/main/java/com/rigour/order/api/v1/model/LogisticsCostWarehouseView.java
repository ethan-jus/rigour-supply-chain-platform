package com.rigour.order.api.v1.model;

public record LogisticsCostWarehouseView(
        String value,
        String label,
        String name,
        String warehouseTypeCode,
        String regionCode,
        String address) {}
