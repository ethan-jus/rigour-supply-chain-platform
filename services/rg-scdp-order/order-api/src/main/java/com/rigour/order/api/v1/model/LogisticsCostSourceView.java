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
        String statusCode,
        String sourceSystem,
        String originalNo,
        java.time.LocalDate documentDate,
        String originAddress,
        String destinationAddress,
        String warehouseTypeCode,
        String targetWarehouseTypeCode) {
    public LogisticsCostSourceView(
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
            String statusCode) {
        this(
                id,
                documentNo,
                sourceType,
                cityCode,
                warehouseId,
                targetWarehouseId,
                senderCode,
                senderName,
                receiverCode,
                receiverName,
                statusCode,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
