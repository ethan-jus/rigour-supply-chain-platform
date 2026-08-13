package com.rigour.erp.domain.model.product;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Integration 归一化后的规格维度及其规格值。 */
public record Specification(
        /** 订货宝规格维度唯一标识。 */
        String sourceId,
        /** 订货宝规格编码。 */
        String code,
        /** 规格名称，例如颜色或尺寸。 */
        String name,
        /** 订货宝父级规格 ID。 */
        String parentSourceId,
        /** 该规格维度下的来源规格值。 */
        List<SpecificationValue> values,
        /** 订货宝原始规格维度字段。 */
        Map<String, Object> sourceFields,
        /** 归一化规格及规格值集合 SHA-256。 */
        String payloadHash) {
    public Specification(String sourceId, String code, String name,
                         List<SpecificationValue> values, String payloadHash) {
        this(sourceId, code, name, null, values, Map.of(), payloadHash);
    }

    public Specification {
        values = values == null ? List.of() : List.copyOf(values);
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
