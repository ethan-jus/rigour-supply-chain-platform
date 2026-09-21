package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * ERP 商品列表视图；列表只保留识别、分类、价格、状态和审计字段。
 *
 * <p>{@code variants} 只在列表查询显式要求携带规格明细时下发，用于列表就地展开规格；
 * 未要求时为空列表，调用方以 {@code variantCount} 判断规格数量。</p>
 */
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
        String productSpecification,
        String unitCode,
        String middleUnitCode,
        BigDecimal baseToMiddleRate,
        String bigUnitCode,
        BigDecimal baseToBigRate,
        /** 默认统计单位层级：BASE/MIDDLE/BIG，空按基础单位。 */
        String statisticsUnitLevel,
        String saleTypeCode,
        String shelfStatusCode,
        Integer ordinal,
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
        List<ProductVariantManagementView> variants,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public ProductManagementSummaryView {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }
}
