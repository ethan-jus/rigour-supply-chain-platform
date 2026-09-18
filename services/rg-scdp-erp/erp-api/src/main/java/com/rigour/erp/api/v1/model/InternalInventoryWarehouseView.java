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
        Instant updatedTime) {
}
