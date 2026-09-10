package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 商品列表视图；列表只保留识别、分类、价格和状态等核心字段。 */
public record ProductManagementSummaryView(
        Long id,
        String productCode,
        String productName,
        String businessLineName,
        Long categoryId,
        String categoryName,
        String categoryNameSnapshot,
        Long brandId,
        String brandName,
        String brandNameSnapshot,
        String industryName,
        String unitCode,
        String saleTypeCode,
        String shelfStatusCode,
        String submitStatusCode,
        String sourceSystemCode,
        String sourceDocumentNo,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        Long defaultWarehouseId,
        String defaultWarehouseName,
        BigDecimal defaultSalePrice,
        String mainImageKey,
        String mainImageUrl,
        Integer variantCount,
        Integer revision,
        Instant updatedTime) {
    public ProductManagementSummaryView(Long id, String productCode, String productName,
                                        Long categoryId, String categoryName, Long brandId,
                                        String brandName, String unitCode, String saleTypeCode,
                                        String shelfStatusCode, String submitStatusCode,
                                        Long defaultWarehouseId, String defaultWarehouseName,
                                        BigDecimal defaultSalePrice, String mainImageKey,
                                        String mainImageUrl, Integer variantCount,
                                        Integer revision, Instant updatedTime) {
        this(id, productCode, productName, null, categoryId, categoryName, null,
                brandId, brandName, null, null, unitCode, saleTypeCode, shelfStatusCode,
                submitStatusCode, null, null, null, null, defaultWarehouseId,
                defaultWarehouseName, defaultSalePrice, mainImageKey, mainImageUrl,
                variantCount, revision, updatedTime);
    }
}
