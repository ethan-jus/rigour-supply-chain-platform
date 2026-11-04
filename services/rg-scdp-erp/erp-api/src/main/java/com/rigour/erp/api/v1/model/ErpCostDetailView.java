package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;

public record ErpCostDetailView(
        String id,
        String documentNo,
        String categoryCode,
        String statusCode,
        int revision,
        ErpCostCommand data,
        List<ErpCostSourceView> sources,
        String warehouseName,
        String ownerEmployeeName,
        Instant createdTime,
        Instant updatedTime,
        String createdBy,
        String updatedBy,
        java.math.BigDecimal periodAmount,
        ProcurementReference procurementReference) {
    public record ProcurementReference(long orderId, String orderCode, long lineId) {}
    public ErpCostDetailView(String id, String documentNo, String categoryCode, String statusCode, int revision, ErpCostCommand data, List<ErpCostSourceView> sources, String warehouseName, String ownerEmployeeName, Instant createdTime, Instant updatedTime, String createdBy, String updatedBy, java.math.BigDecimal periodAmount) {
        this(id,documentNo,categoryCode,statusCode,revision,data,sources,warehouseName,ownerEmployeeName,createdTime,updatedTime,createdBy,updatedBy,periodAmount,null);
    }
    public ErpCostDetailView withPeriodAmount(java.math.BigDecimal value) {
        return new ErpCostDetailView(id, documentNo, categoryCode, statusCode, revision, data, sources, warehouseName, ownerEmployeeName, createdTime, updatedTime, createdBy, updatedBy, value, procurementReference);
    }

    public ErpCostDetailView(
        String id,
        String documentNo,
        String categoryCode,
        String statusCode,
        int revision,
        ErpCostCommand data,
        List<ErpCostSourceView> sources,
        String warehouseName,
        String ownerEmployeeName,
        Instant createdTime,
        Instant updatedTime,
        String createdBy,
        String updatedBy) {
        this(id, documentNo, categoryCode, statusCode, revision, data, sources, warehouseName, ownerEmployeeName, createdTime, updatedTime, createdBy, updatedBy, null);
    }

    public ErpCostDetailView(
            String id,
            String documentNo,
            String categoryCode,
            String statusCode,
            int revision,
            ErpCostCommand data,
            List<ErpCostSourceView> sources,
            String warehouseName,
            String ownerEmployeeName,
            Instant createdTime,
            Instant updatedTime) {
        this(
                id,
                documentNo,
                categoryCode,
                statusCode,
                revision,
                data,
                sources,
                warehouseName,
                ownerEmployeeName,
                createdTime,
                updatedTime,
                null,
                null);
    }
}
