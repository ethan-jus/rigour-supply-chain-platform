package com.rigour.order.api.v1.model;

public record OrderDeliveryCostSourceView(
        Long id,
        String documentNo,
        String cityCode,
        Long warehouseId,
        String customerCode,
        String customerName,
        String statusCode,
        String sourceSystem,
        String originalNo,
        java.time.LocalDate documentDate,
        String destinationAddress) {
    public OrderDeliveryCostSourceView(
            Long id,
            String documentNo,
            String cityCode,
            Long warehouseId,
            String customerCode,
            String customerName,
            String statusCode) {
        this(
                id,
                documentNo,
                cityCode,
                warehouseId,
                customerCode,
                customerName,
                statusCode,
                null,
                null,
                null,
                null);
    }
}
