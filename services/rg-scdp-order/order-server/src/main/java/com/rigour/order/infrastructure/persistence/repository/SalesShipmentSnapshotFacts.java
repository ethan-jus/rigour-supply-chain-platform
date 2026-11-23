package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

/** 来源字段只在本领域转换；不将原始 JSON 或映射检查暴露给业务页面。 */
final class SalesShipmentSnapshotFacts {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SalesShipmentSnapshotFacts() {}

    static List<SalesShipmentLineView> lines(String payload) {
        if (payload == null) return List.of();
        JsonNode detail = JSON.readTree(payload).path("detail");
        JsonNode body = detail.path("body");
        for (String key :
                List.of(
                        "Body",
                        "Products",
                        "OrderProduct",
                        "OrderProducts",
                        "Goods",
                        "list",
                        "details")) if (!body.isArray()) body = detail.path(key);
        if (!body.isArray()) return List.of();
        var rows = new ArrayList<SalesShipmentLineView>();
        int no = 1;
        for (var r : body) {
            String quantity =
                    text(
                            r,
                            "ships_number",
                            "Count",
                            "count",
                            "goods_count",
                            "ships_count",
                            "quantity",
                            "num",
                            "GoodsCount");
            BigDecimal count = quantity == null ? null : new BigDecimal(quantity);
            rows.add(
                    new SalesShipmentLineView(
                            null,
                            null,
                            no++,
                            null,
                            null,
                            text(r, "Coding", "goods_num", "GoodsNo", "ProductCode"),
                            text(
                                    r,
                                    "options_goods_num",
                                    "OptionsGoodsNo",
                                    "options_goods_no",
                                    "sku_no"),
                            text(r, "Name", "GoodsName", "goods_name", "product_name", "name"),
                            specification(r),
                            unit(r),
                            count,
                            text(r, "Remark", "remark")));
        }
        return List.copyOf(rows);
    }

    static String remark(String payload) {
        if (payload == null) return null;
        var root = JSON.readTree(payload);
        var remark = root.path("detail").path("remark");
        if (!remark.isString()) remark = root.path("list").path("remark");
        return remark.isString() ? remark.asText() : null;
    }

    private static String specification(JsonNode r) {
        if (r.path("options_info").isArray()) {
            var names = new ArrayList<String>();
            for (var o : r.path("options_info")) {
                String n = text(o, "options_name");
                if (n != null) names.add(n);
            }
            if (!names.isEmpty()) return String.join(" / ", names);
        }
        return text(r, "OptionsName", "goods_options", "options_name", "specification", "spec");
    }

    private static String unit(JsonNode r) {
        String name = text(r.path("orders_list_info"), "base_units_name", "order_units_name");
        return name == null ? text(r, "Units", "units", "Unit", "unit_name", "unit") : name;
    }

    private static String text(JsonNode r, String... keys) {
        for (String k : keys) {
            var v = r.path(k);
            if (!v.isMissingNode() && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }
}
