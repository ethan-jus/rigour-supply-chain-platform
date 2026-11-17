package com.rigour.integration.application.service.dhb;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** 由服务端原始问题证据确定缺失资料，页面不从错误文案猜类型或来源键。 */
record DhbDependencyRequirement(String type, String sourceId, String salesOrderNo) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static DhbDependencyRequirement from(String code, String expected, String actual) {
        var values = read(actual);
        var required = Objects.toString(read(expected).get("required"), "");
        String orderNo = Objects.toString(values.get("sourceOrderNo"), null);
        if (Set.of(
                        "DHB_SHIPMENT_ORDER_MAPPING_MISSING",
                        "DHB_SHIPMENT_SOURCE_ORDER_UNAVAILABLE",
                        "DHB_SHIPMENT_SOURCE_ORDER_MISMATCH",
                        "DHB_SHIPMENT_ORDER_TARGET_MISSING")
                .contains(Objects.toString(code, "")))
            return orderNo == null
                    ? null
                    : new DhbDependencyRequirement("SALES_ORDER", orderNo, orderNo);
        if (!"DHB_ORDER_MAPPING_MISSING".equals(code)
                && !"DHB_SHIPMENT_WAREHOUSE_MAPPING_MISSING".equals(code)) return null;
        String type =
                required.startsWith("客户")
                        ? "CUSTOMER"
                        : required.startsWith("商品规格")
                                ? "PRODUCT_SKU"
                                : required.startsWith("商品")
                                        ? "PRODUCT_SPU"
                                        : required.contains("仓库") ? "WAREHOUSE" : null;
        if (type == null || !(values.get("sourceIds") instanceof List<?> ids)) return null;
        var keys =
                ids.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .filter(s -> !s.isBlank() && !"0".equals(s))
                        .toList();
        String key =
                "PRODUCT_SKU".equals(type)
                        ? keys.stream().filter(s -> s.contains("::")).findFirst().orElse(null)
                        : keys.stream().findFirst().orElse(null);
        return key == null ? null : new DhbDependencyRequirement(type, key, orderNo);
    }

    private static Map<?, ?> read(String text) {
        if (text == null || text.isBlank()) return Map.of();
        try {
            return JSON.readValue(text, Map.class);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}
