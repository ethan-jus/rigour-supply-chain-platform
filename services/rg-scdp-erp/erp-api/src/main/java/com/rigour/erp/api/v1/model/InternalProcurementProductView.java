package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;

/** 采购商品选择器的一条SKU；单位名称由现有字典解析，不复制到采购表。 */
public record InternalProcurementProductView(
        String productCode,
        String variantCode,
        String productName,
        String specification,
        String defaultUnitCode,
        String baseUnitCode,
        String middleUnitCode,
        BigDecimal baseToMiddleRate,
        String bigUnitCode,
        BigDecimal baseToBigRate,
        BigDecimal purchasePrice) {}
