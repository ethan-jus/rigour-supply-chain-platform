package com.rigour.erp.api.v1.model;

/** 商品图片入参；数据库只保存 COS object key，URL 查询时临时生成。 */
public record ProductImageCommand(
        String imageKey,
        String imageTypeCode,
        Integer ordinal) {
}
