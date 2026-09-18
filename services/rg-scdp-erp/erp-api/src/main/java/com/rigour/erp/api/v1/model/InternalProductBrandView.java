package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 自研商品品牌视图；只暴露品牌维护需要的业务字段。 */
public record InternalProductBrandView(
        Long id,
        String brandCode,
        String brandName,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
}
