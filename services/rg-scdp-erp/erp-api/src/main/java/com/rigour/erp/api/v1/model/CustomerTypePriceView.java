package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 客户类型等级价视图；携带商品与规格展示快照，客户类型名称由前端按 CRM 数据转换。 */
public record CustomerTypePriceView(
        Long id,
        Long productId,
        String productCode,
        String productName,
        Long productVariantId,
        String variantCode,
        String specificationSnapshot,
        String customerTypeCode,
        BigDecimal salePrice,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
}
