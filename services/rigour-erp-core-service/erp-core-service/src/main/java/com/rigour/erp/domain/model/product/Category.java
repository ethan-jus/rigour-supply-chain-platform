package com.rigour.erp.domain.model.product;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
        /** 订货宝原始分类字段。 */
        Map<String, Object> sourceFields,
        /** 归一化分类字段 SHA-256。 */
        String payloadHash) {
    public Category(String sourceId, String externalReferenceId, String name, String payloadHash) {
        this(sourceId, externalReferenceId, name, null, null, null, Map.of(), payloadHash);
    }

    public Category {
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
