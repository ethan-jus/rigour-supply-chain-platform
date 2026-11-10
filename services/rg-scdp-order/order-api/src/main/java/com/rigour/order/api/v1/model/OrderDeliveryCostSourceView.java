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
        String destinationAddress,
        String employeeCode,
        String employeeName,
        Long departmentId,
        String departmentName,
        String contactName,
        String contactPhone,
        java.math.BigDecimal documentAmount) {
    public OrderDeliveryCostSourceView(
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
            String destinationAddress,
            String employeeCode,
            String employeeName,
            Long departmentId,
            String departmentName,
            String contactName,
            String contactPhone) {
        this(
                id,
                documentNo,
                cityCode,
                warehouseId,
                customerCode,
                customerName,
                statusCode,
                sourceSystem,
                originalNo,
                documentDate,
                destinationAddress,
                employeeCode,
                employeeName,
                departmentId,
                departmentName,
                contactName,
                contactPhone,
                null);
    }

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

    public OrderDeliveryCostSourceView(
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
        this(
                id,
                documentNo,
                cityCode,
                warehouseId,
                customerCode,
                customerName,
                statusCode,
                sourceSystem,
                originalNo,
                documentDate,
                destinationAddress,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
