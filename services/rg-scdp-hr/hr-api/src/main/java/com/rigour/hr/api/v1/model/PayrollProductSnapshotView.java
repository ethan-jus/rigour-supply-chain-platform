package com.rigour.hr.api.v1.model;

/** ERP 商品与分类在计提时的快照，不能用商品名称猜测分类或规格。 */
public record PayrollProductSnapshotView(
        Long productId,
        Long variantId,
        String productCode,
        String variantCode,
        String specification,
        Long categoryId,
        String categoryName,
        String categoryPath) {}
