package com.rigour.erp.api.v1.model;

/**
 * ERP 自研仓库保存命令。
 *
 * <p>仓库编码由后端生成；地区、类型和状态分别来自 REGION、WAREHOUSE_TYPE、WAREHOUSE_STATUS 字典。</p>
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
        Integer revision) {
}
