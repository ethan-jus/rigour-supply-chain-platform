package com.rigour.erp.domain.model.product;

/** Integration 归一化后的来源商品分类。 */
public record Category(
        /** 订货宝分类唯一标识。 */
        String sourceId,
        /** 订货宝分类外部引用编码。 */
        String externalReferenceId,
        /** 分类名称。 */
        String name,
        /** 订货宝分类编码。 */
        String categoryNumber,
        /** 订货宝父级分类 ID。 */
        String parentSourceId,
        /** 是否为订货宝默认分类。 */
        Boolean defaultCategory,
        /** 归一化分类字段 SHA-256。 */
        String payloadHash) {
    public Category(String sourceId, String externalReferenceId, String name, String payloadHash) {
        this(sourceId, externalReferenceId, name, null, null, null, payloadHash);
    }
}
