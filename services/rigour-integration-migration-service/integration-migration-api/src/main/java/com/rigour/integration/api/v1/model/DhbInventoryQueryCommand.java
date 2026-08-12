package com.rigour.integration.api.v1.model;

import java.util.List;

/** 订货宝 batchGetStock 批量库存请求。 */
public record DhbInventoryQueryCommand(
        /** 订货宝商品编码集合。 */ List<String> goodsCodes) {
    public DhbInventoryQueryCommand {
        goodsCodes = goodsCodes == null ? List.of() : goodsCodes.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }
}
