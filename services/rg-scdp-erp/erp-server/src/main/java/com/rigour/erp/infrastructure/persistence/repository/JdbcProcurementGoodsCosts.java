package com.rigour.erp.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.ProcurementGoodsCosts;
import com.rigour.erp.domain.cost.ProcurementGoodsSourceRules;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Repository
public class JdbcProcurementGoodsCosts implements ProcurementGoodsCosts {
    private static final String ID =
            "LOWER(CONCAT(SUBSTR(MD5(CONCAT('procurement-cost:',d.tenant_id,':',l.id)),1,8),'-',SUBSTR(MD5(CONCAT('procurement-cost:',d.tenant_id,':',l.id)),9,4),'-',SUBSTR(MD5(CONCAT('procurement-cost:',d.tenant_id,':',l.id)),13,4),'-',SUBSTR(MD5(CONCAT('procurement-cost:',d.tenant_id,':',l.id)),17,4),'-',SUBSTR(MD5(CONCAT('procurement-cost:',d.tenant_id,':',l.id)),21,12)))";
    private static final String SELECT =
            """
SELECT %s id,d.id order_id,l.id line_id,d.tenant_id,d.procurement_no,d.source_system_code,
  d.revision+COALESCE(l.revision,0) revision,d.created_by,d.created_time,d.updated_time,
  d.total_amount order_goods_amount,g.goods_amount,g.unknown_prices,g.line_count,
  COALESCE(d.purchase_date,DATE(d.created_time)) cost_date,
  w.id warehouse_id,w.warehouse_name,w.region_code city_code,w.cost_department_id,s.id supplier_id,s.supplier_name,
  p.id product_id,COALESCE(p.product_name,l.product_name_snapshot,l.product_code) product_name,
  v.id variant_id,v.specification_snapshot specification,l.unit_code,l.quantity,l.unit_price,l.line_amount
FROM erp_procurement_order d
JOIN erp_procurement_order_line l ON l.tenant_id=d.tenant_id AND l.procurement_no=d.procurement_no AND l.deleted=0
JOIN (SELECT tenant_id,procurement_no,SUM(line_amount) goods_amount,
  SUM(CASE WHEN unit_price IS NULL OR unit_price=0 THEN 1 ELSE 0 END) unknown_prices,COUNT(*) line_count
  FROM erp_procurement_order_line WHERE deleted=0 GROUP BY tenant_id,procurement_no) g
  ON g.tenant_id=d.tenant_id AND g.procurement_no=d.procurement_no
JOIN erp_inventory_warehouse w ON w.tenant_id COLLATE utf8mb4_general_ci=d.tenant_id AND w.deleted=0
  AND (w.warehouse_code COLLATE utf8mb4_general_ci=d.target_warehouse_code OR (d.target_warehouse_code IS NULL AND w.id=d.target_warehouse_id))
LEFT JOIN erp_supplier_profile s ON s.tenant_id=d.tenant_id AND s.deleted=0
  AND (s.supplier_code=d.supplier_code OR (d.supplier_code IS NULL AND s.id=d.supplier_id))
LEFT JOIN erp_product p ON p.tenant_id COLLATE utf8mb4_general_ci=l.tenant_id AND p.deleted=0
  AND (p.product_code COLLATE utf8mb4_general_ci=l.product_code OR (l.product_code IS NULL AND p.id=l.product_id))
LEFT JOIN erp_product_variant v ON v.tenant_id COLLATE utf8mb4_general_ci=l.tenant_id AND v.deleted=0
  AND (v.variant_code COLLATE utf8mb4_general_ci=l.variant_code OR (l.variant_code IS NULL AND v.id=l.product_variant_id))
WHERE d.tenant_id=? AND d.deleted=0 AND d.confirmation_status_code='CONFIRMED' AND d.status_code<>'CANCELLED'
"""
                    .formatted(ID);
    // 整单应付包含来源折扣；当前来源 API 没有明细折后金额，成本源按单计入，不猜测商品分摊。
    private static final String ORDER_ID =
            ID.replace("procurement-cost:", "procurement-order-cost:").replace("l.id", "d.id");
    private static final String ORDER_SELECT =
            """
SELECT %s id,d.id order_id,NULL line_id,d.tenant_id,d.procurement_no,d.source_system_code,
  d.revision revision,d.created_by,d.created_time,d.updated_time,
  d.total_amount order_goods_amount,g.goods_amount,g.unknown_prices,g.line_count,
  COALESCE(d.purchase_date,DATE(d.created_time)) cost_date,
  w.id warehouse_id,w.warehouse_name,w.region_code city_code,w.cost_department_id,s.id supplier_id,s.supplier_name,
  NULL product_id,NULL product_name,NULL variant_id,NULL specification,NULL unit_code,
  d.total_quantity quantity,NULL unit_price,NULL line_amount
FROM erp_procurement_order d
LEFT JOIN (SELECT tenant_id,procurement_no,SUM(line_amount) goods_amount,
  SUM(CASE WHEN unit_price IS NULL OR unit_price=0 THEN 1 ELSE 0 END) unknown_prices,COUNT(*) line_count
  FROM erp_procurement_order_line WHERE deleted=0 GROUP BY tenant_id,procurement_no) g
  ON g.tenant_id=d.tenant_id AND g.procurement_no=d.procurement_no
JOIN erp_inventory_warehouse w ON w.tenant_id COLLATE utf8mb4_general_ci=d.tenant_id AND w.deleted=0
  AND (w.warehouse_code COLLATE utf8mb4_general_ci=d.target_warehouse_code OR (d.target_warehouse_code IS NULL AND w.id=d.target_warehouse_id))
LEFT JOIN erp_supplier_profile s ON s.tenant_id=d.tenant_id AND s.deleted=0
  AND (s.supplier_code=d.supplier_code OR (d.supplier_code IS NULL AND s.id=d.supplier_id))
WHERE d.tenant_id=? AND d.deleted=0 AND d.confirmation_status_code='CONFIRMED' AND d.status_code<>'CANCELLED'
"""
                    .formatted(ORDER_ID);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ErpWarehouseDataScope scope;

    public JdbcProcurementGoodsCosts(
            JdbcTemplate jdbc, ObjectMapper json, ErpWarehouseDataScope scope) {
        this.jdbc = jdbc;
        this.json = json;
        this.scope = scope;
    }

    public List<Map<String, String>> page(String tenant, String after) {
        return jdbc
                .queryForList(
                        ORDER_SELECT
                                + " AND "
                                + ORDER_ID
                                + ">? ORDER BY "
                                + ORDER_ID
                                + " LIMIT 1000",
                        tenant,
                        after)
                .stream()
                .map(this::fact)
                .toList();
    }

    public String version(String tenant) {
        List<String> parts = new ArrayList<>(List.of("goods-order-payable-v3"));
        for (String table :
                List.of(
                        "erp_procurement_order",
                        "erp_procurement_order_line",
                        "erp_supplier_profile",
                        "erp_product",
                        "erp_product_variant",
                        "erp_inventory_warehouse")) {
            parts.add(
                    jdbc.queryForObject(
                            "SELECT"
                                + " CONCAT(COUNT(*),':',COALESCE(SUM(revision),0),':',COALESCE(SUM(UNIX_TIMESTAMP(updated_time)*1000000),0))"
                                + " FROM "
                                    + table
                                    + " WHERE tenant_id=?",
                            String.class,
                            tenant));
        }
        return UUID.nameUUIDFromBytes(
                        String.join("/", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    public Map<String, String> warehouseDepartments(String tenant) {
        Map<String, String> result = new HashMap<>();
        for (var row :
                jdbc.queryForList(
                        "SELECT id,cost_department_id FROM erp_inventory_warehouse WHERE"
                            + " tenant_id=? AND deleted=0",
                        tenant))
            if (row.get("cost_department_id") != null)
                result.put(row.get("id").toString(), row.get("cost_department_id").toString());
        return result;
    }

    public Optional<ErpCostDetailView> detail(String tenant, String id, String action) {
        var rows = jdbc.queryForList(ORDER_SELECT + " AND " + ORDER_ID + "=?", tenant, id);
        // 保留旧商品行成本链接的只读追溯；分析源已改为一单一笔折后货款。
        if (rows.isEmpty()) rows = jdbc.queryForList(SELECT + " AND " + ID + "=?", tenant, id);
        if (rows.isEmpty()) return Optional.empty();
        var r = rows.getFirst();
        scope.requireWarehouse(tenant, ((Number) r.get("warehouse_id")).longValue(), action);
        var cityQuery = new QueryWrapper<>().eq("region_code", r.get("city_code"));
        scope.applyCity(cityQuery, action, "region_code");
        // Reuse the same city predicate as the manual ledger, binding all values.
        var citySql = cityQuery.getSqlSegment();
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.add(r.get("warehouse_id"));
        var names = cityQuery.getParamNameValuePairs();
        var m =
                java.util.regex.Pattern.compile("#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
                        .matcher(citySql);
        StringBuilder bound = new StringBuilder();
        while (m.find()) {
            args.add(names.get(m.group(1)));
            m.appendReplacement(bound, "?");
        }
        m.appendTail(bound);
        var allowed =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM erp_inventory_warehouse WHERE tenant_id=? AND id=?"
                                + " AND "
                                + bound,
                        Integer.class,
                        args.toArray());
        if (allowed == null || allowed == 0)
            throw new com.rigour.shared.context.AuthorizationDeniedException(action);
        var f = fact(r);
        var detailData = (ObjectNode) json.readTree(f.get("data_json"));
        detailData.remove(
                List.of(
                        "_procurementOrderId",
                        "_procurementOrderCode",
                        "_procurementLineId",
                        "_procurementAmountBasis",
                        "_costTreatment",
                        "_costTreatmentReason"));
        var command = json.treeToValue(detailData, ErpCostCommand.class);
        return Optional.of(
                new ErpCostDetailView(
                        id,
                        f.get("document_no"),
                        "PURCHASE",
                        "CONFIRMED",
                        Integer.parseInt(f.get("revision")),
                        command,
                        List.of(),
                        f.get("warehouse_name"),
                        null,
                        instant(r.get("created_time")),
                        instant(r.get("updated_time")),
                        f.get("created_by"),
                        f.get("created_by"),
                        null,
                        new ErpCostDetailView.ProcurementReference(
                                ((Number) r.get("order_id")).longValue(),
                                r.get("procurement_no").toString(),
                                number(r, "line_id"))));
    }

    public String reconcileLegacy(String tenant, String raw) {
        var data = (ObjectNode) json.readTree(raw);
        if ("PROCUREMENT_FREIGHT".equals(data.path("typeCode").asText())) return raw;
        var id = data.path("goods").path("purchaseOrderId");
        if (!id.isNumber()) return raw;
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM erp_procurement_order WHERE tenant_id=? AND id=? AND"
                                + " deleted=0 AND confirmation_status_code='CONFIRMED'",
                        Integer.class,
                        tenant,
                        id.asLong());
        if (count != null && count > 0) {
            data.put("_costTreatment", "REFERENCE");
            data.put("_costTreatmentReason", "已有已确认采购单货款来源；原人工采购记录保留待核对，不重复计入货款");
            return json.writeValueAsString(data);
        }
        return raw;
    }

    private Map<String, String> fact(Map<String, Object> r) {
        BigDecimal price = (BigDecimal) r.get("unit_price");
        String system = Objects.toString(r.get("source_system_code"), "SYSTEM");
        String issue =
                ProcurementGoodsSourceRules.issue(
                        system,
                        r.get("line_count") == null
                                ? 0L
                                : ((Number) r.get("line_count")).longValue(),
                        r.get("unknown_prices") == null
                                ? 0L
                                : ((Number) r.get("unknown_prices")).longValue(),
                        (BigDecimal) r.get("order_goods_amount"),
                        (BigDecimal) r.get("goods_amount"));
        boolean unknown = issue != null;
        boolean orderLevel = r.get("line_id") == null;
        BigDecimal amount =
                unknown
                        ? null
                        : (BigDecimal) r.get(orderLevel ? "order_goods_amount" : "line_amount");
        var goods =
                new ErpCostGoodsDetails(
                        number(r, "product_id"),
                        number(r, "variant_id"),
                        Objects.toString(r.get("product_name"), null),
                        Objects.toString(r.get("specification"), null),
                        Objects.toString(r.get("unit_code"), null),
                        price,
                        (BigDecimal) r.get("quantity"),
                        number(r, "supplier_id"),
                        Objects.toString(r.get("supplier_name"), null),
                        number(r, "warehouse_id"),
                        Objects.toString(r.get("warehouse_name"), null),
                        number(r, "order_id"),
                        null,
                        unknown
                                ? ("AMOUNT_MISMATCH".equals(issue)
                                        ? "采购单货款总额与明细合计不一致，请修正原采购数据"
                                        : "采购来源缺价或缺少明细，请完善原采购数据")
                                : (orderLevel ? "按整单折后应付计入货款，未分摊到商品。" : null),
                        null);
        String id = r.get("id").toString();
        LocalDate date = ((java.sql.Date) r.get("cost_date")).toLocalDate();
        var command =
                new ErpCostCommand(
                        id,
                        null,
                        "采购货款-" + r.get("procurement_no"),
                        "PURCHASE",
                        Objects.toString(r.get("city_code"), null),
                        Objects.toString(r.get("city_code"), null),
                        null,
                        null,
                        date,
                        amount,
                        "UNKNOWN",
                        null,
                        "货款按已确认采购单整单折后应付计入，商品明细保留折前价，不分摊整单折扣；约定运费不重复累计。",
                        null,
                        goods,
                        null,
                        null,
                        null);
        ObjectNode data = (ObjectNode) json.valueToTree(command);
        data.put("_procurementOrderId", number(r, "order_id"));
        data.put("_procurementOrderCode", r.get("procurement_no").toString());
        data.put("_procurementLineId", number(r, "line_id"));
        if (!orderLevel) {
            data.put("_costTreatment", "REFERENCE");
            data.put("_costTreatmentReason", "旧商品行仅保留来源追溯；货款已按整单折后应付计入，不重复累计。");
        }
        if (orderLevel) data.put("_procurementAmountBasis", "ORDER_PAYABLE");
        if (issue != null) {
            data.put("_costTreatment", "REVIEW");
            data.put("_costTreatmentReason", goods.amountReason());
        }
        Map<String, String> f = new LinkedHashMap<>();
        f.put("id", id);
        f.put("tenant_id", r.get("tenant_id").toString());
        f.put("document_no", r.get("procurement_no") + (orderLevel ? "" : "/" + r.get("line_id")));
        f.put("title", command.title());
        f.put("type_code", "PURCHASE");
        f.put("city_code", command.cityCode());
        f.put("department_id", Objects.toString(r.get("cost_department_id"), null));
        f.put("cost_date", date.toString());
        f.put("amount", amount == null ? null : amount.toPlainString());
        f.put("status_code", "CONFIRMED");
        f.put("payment_status_code", "UNKNOWN");
        f.put("data_json", json.writeValueAsString(data));
        f.put("revision", r.get("revision").toString());
        f.put("created_by", Objects.toString(r.get("created_by"), "SYSTEM"));
        f.put("updated_time", Objects.toString(r.get("updated_time"), null));
        f.put("category_code", "PURCHASE");
        f.put("source_system", system);
        f.put("warehouse_id", r.get("warehouse_id").toString());
        f.put("warehouse_name", Objects.toString(r.get("warehouse_name"), null));
        f.put("sources_json", "[]");
        return f;
    }

    private static Long number(Map<String, Object> r, String key) {
        return r.get(key) == null ? null : ((Number) r.get(key)).longValue();
    }

    private static Instant instant(Object value) {
        return value instanceof java.sql.Timestamp t
                ? t.toInstant()
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
}
