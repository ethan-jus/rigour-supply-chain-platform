package com.rigour.erp.api.v1.model;

/**
 * ERP 自研仓库保存命令。
 *
 * <p>仓库编码由后端生成；地区、类型和状态分别来自 REGION、WAREHOUSE_TYPE、WAREHOUSE_STATUS 字典。
 */
public record InternalInventoryWarehouseCommand(
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
        String locationAreaCode,
        String locationAreaVersion,
        Long costDepartmentId) {
    /** 兼容旧客户端；未传所在地字段时，编辑保留已保存的所在地。 */
    public InternalInventoryWarehouseCommand(
            String warehouseName,
            String regionCode,
            String warehouseTypeCode,
            Boolean defaultFlag,
            String address,
            String contactName,
            String contactPhone,
            String statusCode,
            String remark,
            Integer revision) {
        this(
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
                null,
                null);
    }

    public InternalInventoryWarehouseCommand(
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
            String locationAreaCode,
            String locationAreaVersion) {
        this(
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
                locationAreaCode,
                locationAreaVersion,
                null);
    }
}
