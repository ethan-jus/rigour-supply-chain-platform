package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ErpCostCommand(
        String requestId,
        Integer revision,
        String title,
        String typeCode,
        String cityCode,
        String cityName,
        Long departmentId,
        String ownerEmployeeCode,
        LocalDate costDate,
        BigDecimal amount,
        String paymentStatusCode,
        ErpCostRentalDetails rental,
        String remark,
        String reason,
        ErpCostGoodsDetails goods,
        String costMonth,
        String ownerName,
        String payeeName,
        ErpCostMarketingDetails marketing,
        java.util.List<ErpCostExternalDocument> externalDocuments,
        WarehouseCostLedger warehouseLedger) {
    public ErpCostCommand(
        String requestId,
        Integer revision,
        String title,
        String typeCode,
        String cityCode,
        String cityName,
        Long departmentId,
        String ownerEmployeeCode,
        LocalDate costDate,
        BigDecimal amount,
        String paymentStatusCode,
        ErpCostRentalDetails rental,
        String remark,
        String reason,
        ErpCostGoodsDetails goods,
        String costMonth,
        String ownerName,
        String payeeName,
        ErpCostMarketingDetails marketing,
        java.util.List<ErpCostExternalDocument> externalDocuments) {
        this(requestId, revision, title, typeCode, cityCode, cityName, departmentId, ownerEmployeeCode, costDate, amount, paymentStatusCode, rental, remark, reason, goods, costMonth, ownerName, payeeName, marketing, externalDocuments, null);
    }
    public ErpCostCommand withWarehouseLedger(WarehouseCostLedger value, BigDecimal total) {
        return new ErpCostCommand(requestId, revision, title, typeCode, cityCode, cityName, departmentId, ownerEmployeeCode, costDate, total, paymentStatusCode, rental, remark, reason, goods, costMonth, ownerName, payeeName, marketing, externalDocuments, value);
    }

    public ErpCostCommand(
            String requestId,
            Integer revision,
            String title,
            String typeCode,
            String cityCode,
            String cityName,
            Long departmentId,
            String ownerEmployeeCode,
            LocalDate costDate,
            BigDecimal amount,
            String paymentStatusCode,
            ErpCostRentalDetails rental,
            String remark,
            String reason,
            ErpCostGoodsDetails goods,
            String costMonth,
            String ownerName,
            String payeeName) {
        this(
                requestId,
                revision,
                title,
                typeCode,
                cityCode,
                cityName,
                departmentId,
                ownerEmployeeCode,
                costDate,
                amount,
                paymentStatusCode,
                rental,
                remark,
                reason,
                goods,
                costMonth,
                ownerName,
                payeeName,
                null,
                null);
    }

    public ErpCostCommand(
            String requestId,
            Integer revision,
            String title,
            String typeCode,
            String cityCode,
            String cityName,
            Long departmentId,
            String ownerEmployeeCode,
            LocalDate costDate,
            BigDecimal amount,
            String paymentStatusCode,
            ErpCostRentalDetails rental,
            String remark,
            String reason) {
        this(
                requestId,
                revision,
                title,
                typeCode,
                cityCode,
                cityName,
                departmentId,
                ownerEmployeeCode,
                costDate,
                amount,
                paymentStatusCode,
                rental,
                remark,
                reason,
                null,
                null,
                null,
                null);
    }
}
