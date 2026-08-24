package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 自研商品分类视图；用于分类列表、详情和商品编辑下拉选择。 */
public record InternalProductCategoryView(
        Long id,
        String categoryCode,
        String categoryName,
        Long parentId,
        Integer categoryLevel,
        Integer ordinal,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
}
