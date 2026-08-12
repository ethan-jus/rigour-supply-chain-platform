package com.rigour.erp.domain.model.product;

/** Integration 归一化后的单个规格值。 */
public record SpecificationValue(
        /** 订货宝规格值唯一标识。 */
        String sourceId,
        /** 订货宝规格值编码。 */
        String code,
        /** 规格值名称，例如红色或 XL。 */
        String name,
        /** 所属订货宝规格维度来源 ID。 */
        String parentSourceId,
        /** 归一化规格值字段 SHA-256。 */
        String payloadHash) {
}
