package com.rigour.erp.domain.model.supply;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** ERP 仓库、商品、规格组合粒度的库存余额快照。 */
public record InventoryBalance(String goodsGuid, String goodsCode, String goodsName,
                               String warehouseGuid, String warehouseCode, String warehouseName,
                               String firstOptionGuid, String firstOptionCode, String firstOptionName,
                               String secondOptionGuid, String secondOptionCode, String secondOptionName,
                               BigDecimal availableQuantity, BigDecimal realQuantity,
                               Map<String, Object> sourceFields, String payloadHash) {
    public InventoryBalance {
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
