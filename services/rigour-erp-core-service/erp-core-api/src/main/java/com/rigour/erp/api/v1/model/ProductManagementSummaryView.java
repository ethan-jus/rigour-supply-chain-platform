package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 商品列表视图；列表只保留识别、分类、价格和状态等核心字段。 */
public record ProductManagementSummaryView(
        Long id,
        String productCode,
        String productName,
        Long categoryId,
        String categoryName,
        Long brandId,
        String brandName,
        String unitCode,
        String saleTypeCode,
        String shelfStatusCode,
        String submitStatusCode,
        Long defaultWarehouseId,
        String defaultWarehouseName,
        BigDecimal defaultSalePrice,
        String mainImageKey,
        String mainImageUrl,
        Integer variantCount,
        Integer revision,
        Instant updatedTime) {
}
