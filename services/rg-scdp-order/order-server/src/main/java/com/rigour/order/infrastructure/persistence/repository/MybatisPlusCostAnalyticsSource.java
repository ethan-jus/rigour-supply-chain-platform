package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.order.application.port.out.AnalyticsSourceSnapshotStore.Page;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostEntity;
import com.rigour.order.infrastructure.persistence.mapper.OrderLogisticsCostMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 成本事实使用现有 BaseMapper；TEXT_UUID 的数据库排序与游标采用相同的规范 UUID 文本。 */
@Repository
public class MybatisPlusCostAnalyticsSource {
    public static final String DATASET = "ORDER_LOGISTICS_COST";
    public static final List<String> COLUMNS =
            List.of(
                    "id",
                    "tenant_id",
                    "document_no",
                    "title",
                    "type_code",
                    "city_code",
                    "department_id",
                    "cost_date",
                    "amount",
                    "status_code",
                    "payment_status_code",
                    "data_json",
                    "revision",
                    "created_by",
                    "updated_time",
                    "category_code",
                    "payer_code",
                    "source_system",
                    "owner_employee_code",
                    "owner_employee_name",
                    "warehouse_id",
                    "source_warehouse_id",
                    "warehouse_name",
                    "sources_json");
    private final OrderLogisticsCostMapper mapper;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private tools.jackson.databind.ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    void attribution(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            tools.jackson.databind.ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public MybatisPlusCostAnalyticsSource(OrderLogisticsCostMapper mapper) {
        this.mapper = mapper;
    }

    public String version(String tenant) {
        String base = mapper.analyticsVersion(tenant) + ":transport-types-v2";
        if (jdbc == null) return base;
        return base
                + ":sales-attribution-v1:"
                + jdbc.queryForObject(
                        "SELECT CONCAT(COUNT(*),':',COALESCE(SUM(revision),0)) FROM"
                            + " order_attribution_snapshot WHERE tenant_id=?",
                        String.class,
                        tenant)
                + ":"
                + jdbc.queryForObject(
                        "SELECT CONCAT(COUNT(*),':',COALESCE(SUM(revision),0)) FROM"
                            + " order_sales_order WHERE tenant_id=?",
                        String.class,
                        tenant);
    }

    @Transactional(readOnly = true)
    public Page page(String tenant, String after) {
        String cursor = after == null ? "" : after.toLowerCase(Locale.ROOT);
        if (!cursor.isEmpty()
                && !cursor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("TEXT_UUID源游标无效");
        String version = version(tenant);
        var query =
                new QueryWrapper<OrderLogisticsCostEntity>()
                        .select(COLUMNS.toArray(String[]::new))
                        .eq("tenant_id", tenant)
                        .eq("deleted", 0)
                        .gt(!cursor.isEmpty(), "id", cursor)
                        .orderByAsc("id")
                        .last("LIMIT 1000");
        List<Map<String, String>> items = new ArrayList<>();
        for (var row : mapper.selectMaps(query)) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String column : COLUMNS) values.put(column, exact(row.get(column)));
            items.add(values);
        }
        enrichLegacyAttribution(tenant, items);
        return new Page(version, items);
    }

    private void enrichLegacyAttribution(String tenant, List<Map<String, String>> items) {
        if (jdbc == null) return;
        Map<Map<String, String>, tools.jackson.databind.JsonNode> sources = new IdentityHashMap<>();
        Set<String> codes = new LinkedHashSet<>();
        for (var row : items) {
            if (row.get("sources_json") == null
                    || row.get("data_json") == null
                    || !"DELIVERY"
                            .equals(
                                    json.readTree(row.get("data_json"))
                                            .path("ledger")
                                            .path("transportPurposeCode")
                                            .asText())) continue;
            var links = json.readTree(row.get("sources_json"));
            sources.put(row, links);
            for (var link : links)
                if ("SALES_ORDER".equals(link.path("orderTypeCode").asText())
                        && !link.hasNonNull("employeeCode"))
                    codes.add(link.path("orderCode").asText());
        }
        Map<String, Map<String, Object>> values = new HashMap<>();
        var list = new ArrayList<>(codes);
        for (int offset = 0; offset < list.size(); offset += 500) {
            var part = list.subList(offset, Math.min(offset + 500, list.size()));
            var args = new ArrayList<Object>();
            args.add(tenant);
            args.addAll(part);
            for (var row :
                    jdbc.queryForList(
                            "SELECT o.order_no,COALESCE(a.employee_code,o.owner_employee_code)"
                                + " employee_code,COALESCE(a.employee_name,o.owner_employee_name_snapshot)"
                                + " employee_name,a.department_id,a.department_name FROM"
                                + " order_sales_order o LEFT JOIN order_attribution_snapshot a ON"
                                + " a.tenant_id=o.tenant_id AND a.order_id=o.id AND"
                                + " a.state='FROZEN' WHERE o.tenant_id=? AND o.deleted=0 AND"
                                + " o.order_no IN ("
                                    + String.join(",", Collections.nCopies(part.size(), "?"))
                                    + ")",
                            args.toArray())) values.put(row.get("order_no").toString(), row);
        }
        sources.forEach(
                (row, links) -> {
                    for (var link : links)
                        if (!link.hasNonNull("employeeCode")) {
                            var value = values.get(link.path("orderCode").asText());
                            if (value == null) continue;
                            var node = (tools.jackson.databind.node.ObjectNode) link;
                            node.put(
                                    "employeeCode",
                                    Objects.toString(value.get("employee_code"), null));
                            node.put(
                                    "employeeName",
                                    Objects.toString(value.get("employee_name"), null));
                            if (value.get("department_id") != null)
                                node.put(
                                        "departmentId",
                                        ((Number) value.get("department_id")).longValue());
                            node.put(
                                    "departmentName",
                                    Objects.toString(value.get("department_name"), null));
                        }
                    row.put("sources_json", json.writeValueAsString(links));
                });
    }

    private static String exact(Object v) {
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal d) return d.toPlainString();
        if (v instanceof byte[] bytes)
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return v.toString();
    }
}
