package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ERP 商品详情视图；只展示我方商品管理需要的结构化字段。 */
public record ProductManagementDetailView(
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
        BigDecimal minOrderQuantity,
        Boolean orderMultipleFlag,
        BigDecimal orderMultipleQuantity,
        String saleTypeCode,
        String shelfStatusCode,
        String sourceStatusName,
        List<String> tagCodes,
        BigDecimal limitQuantity,
        Long defaultWarehouseId,
        String defaultWarehouseName,
        List<ProductImageManagementView> images,
        List<ProductVariantManagementView> variants,
        List<Long> recommendProductIds,
        String submitStatusCode,
        String remark,
        String sourceSystemCode,
        String sourceDocumentNo,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        Map<String, Object> sourceFields,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public ProductManagementDetailView {
        tagCodes = tagCodes == null ? List.of() : List.copyOf(tagCodes);
        images = images == null ? List.of() : List.copyOf(images);
        variants = variants == null ? List.of() : List.copyOf(variants);
        recommendProductIds = recommendProductIds == null ? List.of() : List.copyOf(recommendProductIds);
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }

    public ProductManagementDetailView(Long id, String productCode, String productName,
                                       Long categoryId, String categoryName, Long brandId,
                                       String brandName, String productSpecification,
                                       String unitCode, BigDecimal minOrderQuantity,
                                       Boolean orderMultipleFlag, BigDecimal orderMultipleQuantity,
                                       String saleTypeCode, String shelfStatusCode,
                                       List<String> tagCodes, BigDecimal limitQuantity,
                                       Long defaultWarehouseId, String defaultWarehouseName,
                                       List<ProductImageManagementView> images,
                                       List<ProductVariantManagementView> variants,
                                       List<Long> recommendProductIds, String submitStatusCode,
                                       String remark, Integer revision, String createdBy,
                                       Instant createdTime, String updatedBy, Instant updatedTime) {
        this(id, productCode, productName, null, categoryId, categoryName, null,
                brandId, brandName, null, null, productSpecification, unitCode,
                minOrderQuantity, orderMultipleFlag, orderMultipleQuantity, saleTypeCode,
                shelfStatusCode, null, tagCodes, limitQuantity, defaultWarehouseId,
                defaultWarehouseName, images, variants, recommendProductIds, submitStatusCode,
                remark, null, null, null, null, Map.of(), revision, createdBy,
                createdTime, updatedBy, updatedTime);
    }
}
