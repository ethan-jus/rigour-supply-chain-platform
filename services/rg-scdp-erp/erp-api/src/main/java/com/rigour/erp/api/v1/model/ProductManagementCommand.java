package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * ERP 商品保存/提交命令。
 *
 * <p>submit=false 或为空表示保存草稿，只做基础格式校验；submit=true 表示提交，
 * 会校验商品名称、分类、品牌、单位、仓库和规格价格等必填项。</p>
 */
public record ProductManagementCommand(
        Boolean submit,
        String productName,
        Long categoryId,
        Long brandId,
        String productSpecification,
        String unitCode,
        /** 中包装单位编码，关联 PRODUCT_UNIT 字典项；与换算率成对维护。 */
        String middleUnitCode,
        /** 1 中包装 = N 基础单位。 */
        BigDecimal baseToMiddleRate,
        /** 大包装单位编码，关联 PRODUCT_UNIT 字典项；与换算率成对维护。 */
        String bigUnitCode,
        /** 1 大包装 = N 基础单位。 */
        BigDecimal baseToBigRate,
        /** 默认统计单位层级：BASE/MIDDLE/BIG；空按基础单位。 */
        String statisticsUnitLevel,
        BigDecimal minOrderQuantity,
        Boolean orderMultipleFlag,
        BigDecimal orderMultipleQuantity,
        String saleTypeCode,
        String shelfStatusCode,
        Integer ordinal,
        List<String> tagCodes,
        BigDecimal limitQuantity,
        Long defaultWarehouseId,
        List<ProductImageCommand> images,
        List<ProductVariantCommand> variants,
        List<Long> recommendProductIds,
        String remark,
        Integer revision) {
}
