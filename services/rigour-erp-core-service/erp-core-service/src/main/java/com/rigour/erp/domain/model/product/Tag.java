package com.rigour.erp.domain.model.product;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Integration 归一化后的来源商品标签。 */
public record Tag(
        /** 订货宝标签唯一标识。 */
        String sourceId,
        /** 订货宝标签编码。 */
        String code,
        /** 标签名称。 */
        String name,
        /** 标签排序。 */
        Integer sortOrder,
        /** 被商品关联的数量，仅为订货宝统计快照。 */
        Integer relationCount,
        /** 订货宝创建时间。 */
        Instant createdAt,
        /** 订货宝更新时间。 */
        Instant updatedAt,
        /** 标签分组来源 ID。 */
        String groupSourceId,
        /** 标签分组名称。 */
        String groupName,
        /** 订货宝原始标签字段。 */
        Map<String, Object> sourceFields,
        /** 归一化标签字段 SHA-256。 */
        String payloadHash) {
    public Tag(String sourceId, String code, String name, String payloadHash) {
        this(sourceId, code, name, null, null, null, null, null, null, Map.of(), payloadHash);
    }

    public Tag {
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
