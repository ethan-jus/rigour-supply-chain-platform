package com.rigour.erp.api.v1.model;

/** 商品图片视图；imageUrl 是按 imageKey 生成的短时访问地址，失败时为空。 */
public record ProductImageManagementView(
        String imageKey,
        String imageUrl,
        String imageTypeCode,
        Integer ordinal) {
}
