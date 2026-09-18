package com.rigour.erp.domain.model.product;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Integration 归一化后的来源商品品牌。 */
public record Brand(
        /** 订货宝品牌唯一标识。 */
        String sourceId,
        /** 订货宝品牌外部引用编码。 */
        String externalReferenceId,
        /** 品牌名称。 */
        String name,
        /** 订货宝品牌编码。 */
        String brandNumber,
        /** 品牌排序。 */
        Integer sortOrder,
        /** 品牌说明。 */
        String description,
        /** 订货宝原始品牌字段。 */
        Map<String, Object> sourceFields,
        /** 归一化品牌字段 SHA-256。 */
        String payloadHash) {
    public Brand(String sourceId, String externalReferenceId, String name, String payloadHash) {
        this(sourceId, externalReferenceId, name, null, null, null, Map.of(), payloadHash);
    }

    public Brand {
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
