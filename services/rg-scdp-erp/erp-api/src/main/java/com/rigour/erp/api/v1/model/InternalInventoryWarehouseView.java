package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 自研仓库视图；用于仓库维护、商品归属仓库和库存业务选择。 */
public record InternalInventoryWarehouseView(
        Long id,
        String warehouseCode,
        String warehouseName,
        String regionCode,
        String warehouseTypeCode,
        Boolean defaultFlag,
        String address,
        String contactName,
        String contactPhone,
        String statusCode,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime,
        String locationAreaCode,
        String locationAreaVersion,
        String locationDisplay,
        java.util.List<SourceReference> sourceReferences) {
    public record SourceReference(
            String sourceSystem,
            String sourceObjectId,
            String sourceCode,
            String sourceName,
            String connectorId) {}

    public InternalInventoryWarehouseView {
        sourceReferences =
                sourceReferences == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(sourceReferences);
    }

    public InternalInventoryWarehouseView(
            Long id,
            String warehouseCode,
            String warehouseName,
            String regionCode,
            String warehouseTypeCode,
            Boolean defaultFlag,
            String address,
            String contactName,
            String contactPhone,
            String statusCode,
            String remark,
            Integer revision,
            String createdBy,
            Instant createdTime,
            String updatedBy,
            Instant updatedTime,
            String locationAreaCode,
            String locationAreaVersion,
            String locationDisplay) {
        this(
                id,
                warehouseCode,
                warehouseName,
                regionCode,
                warehouseTypeCode,
                defaultFlag,
                address,
                contactName,
                contactPhone,
                statusCode,
                remark,
                revision,
                createdBy,
                createdTime,
                updatedBy,
                updatedTime,
                locationAreaCode,
                locationAreaVersion,
                locationDisplay,
                java.util.List.of());
    }

    public InternalInventoryWarehouseView withSourceReferences(
            java.util.List<SourceReference> refs) {
        return new InternalInventoryWarehouseView(
                id,
                warehouseCode,
                warehouseName,
                regionCode,
                warehouseTypeCode,
                defaultFlag,
                address,
                contactName,
                contactPhone,
                statusCode,
                remark,
                revision,
                createdBy,
                createdTime,
                updatedBy,
                updatedTime,
                locationAreaCode,
                locationAreaVersion,
                locationDisplay,
                refs);
    }

    public InternalInventoryWarehouseView(
            Long id,
            String warehouseCode,
            String warehouseName,
            String regionCode,
            String warehouseTypeCode,
            Boolean defaultFlag,
            String address,
            String contactName,
            String contactPhone,
            String statusCode,
            String remark,
            Integer revision,
            String createdBy,
            Instant createdTime,
            String updatedBy,
            Instant updatedTime) {
        this(
                id,
                warehouseCode,
                warehouseName,
                regionCode,
                warehouseTypeCode,
                defaultFlag,
                address,
                contactName,
                contactPhone,
                statusCode,
                remark,
                revision,
                createdBy,
                createdTime,
                updatedBy,
                updatedTime,
                null,
                null,
                null);
    }

    public InternalInventoryWarehouseView withLocationDisplay(String display) {
        return new InternalInventoryWarehouseView(
                id,
                warehouseCode,
                warehouseName,
                regionCode,
                warehouseTypeCode,
                defaultFlag,
                address,
                contactName,
                contactPhone,
                statusCode,
                remark,
                revision,
                createdBy,
                createdTime,
                updatedBy,
                updatedTime,
                locationAreaCode,
                locationAreaVersion,
                display,
                sourceReferences);
    }
}
