package com.rigour.erp.api.v1.model;

/** ERP 商品图片投影；只返回本次请求生成的短时访问地址，不暴露私桶对象 key。 */
public record ProductImageView(
        /** ERP 图片记录 UUID。 */
        String id,
        /** 订货宝图片资源 ID。 */
        String sourceResourceId,
        /** 订货宝商品主键。 */
        String sourceGoodsId,
        /** 图片原名。 */
        String originalName,
        /** 图片完整来源地址。 */
        String sourceFileName,
        /** 本次请求生成的短时 URL。 */
        String url,
        /** 展示排序。 */
        int sortOrder,
        /** 是否主图。 */
        boolean primary) {
}
