package com.rigour.erp.domain.model.product;

/** 商品图片对象；业务库只保存 COS 私桶 key，不保存第三方 URL 或永久访问地址。 */
public record ProductImage(
        /** 订货宝图片资源 ID。 */
        String sourceResourceId,
        /** 订货宝商品 ID。 */
        String sourceGoodsId,
        /** 订货宝原始文件名。 */
        String originalName,
        /** 订货宝文件名或路径快照。 */
        String fileName,
        /** 来源图片排序。 */
        Integer sortOrder,
        /** 我方 COS 私桶对象 key。 */
        String objectKey) {
}
