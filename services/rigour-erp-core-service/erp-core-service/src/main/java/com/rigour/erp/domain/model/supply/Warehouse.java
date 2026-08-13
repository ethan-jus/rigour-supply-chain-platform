package com.rigour.erp.domain.model.supply;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** ERP 仓库导入模型。 */
public record Warehouse(String sourceId, String sourceGuid, String code, String name,
                        String sourceStatus, Boolean defaultFlag, BigDecimal acreage,
                        String phone, String address, String collaboratorSourceId,
                        String remark, Map<String, Object> sourceFields, String payloadHash) {
    public Warehouse {
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
