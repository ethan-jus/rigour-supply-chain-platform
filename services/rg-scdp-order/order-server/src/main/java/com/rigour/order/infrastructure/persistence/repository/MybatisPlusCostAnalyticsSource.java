package com.rigour.order.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.order.api.v1.model.LogisticsImportModels.CarrierOrder;
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
    private final tools.jackson.databind.ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    void attribution(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MybatisPlusCostAnalyticsSource(
            OrderLogisticsCostMapper mapper, tools.jackson.databind.ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public String version(String tenant) {
        String base = mapper.analyticsVersion(tenant) + ":transport-types-v6-payment";
        if (jdbc == null) return base;
        return base
                + ":cost-revisions:"
                + jdbc.queryForObject(
                        "SELECT CONCAT(COUNT(*),':',COALESCE(SUM(revision),0)) FROM"
                                + " order_logistics_cost WHERE tenant_id=?",
                        String.class,
                        tenant)
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
        var columns = new ArrayList<>(COLUMNS);
        columns.add(
                "CASE WHEN status_code='VOID' THEN NULL ELSE "
                        + MybatisPlusOrderLogisticsCostRepository.missingBusinessSql()
                        + " END AS transport_missing");
        var query =
                new QueryWrapper<OrderLogisticsCostEntity>()
                        .select(columns.toArray(String[]::new))
                        .eq("tenant_id", tenant)
                        .eq("deleted", 0)
                        .gt(!cursor.isEmpty(), "id", cursor)
                        .orderByAsc("id")
                        .last("LIMIT 1000");
        List<Map<String, String>> items = new ArrayList<>();
        for (var row : mapper.selectMaps(query)) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String column : COLUMNS) values.put(column, exact(row.get(column)));
            String missing = exact(row.get("transport_missing"));
            if (!"VOID".equals(values.get("status_code"))) {
                boolean complete = missing != null && missing.isEmpty();
                values.put("status_code", complete ? "CONFIRMED" : "DRAFT");
                if (missing != null && !complete) {
                    var data =
                            (tools.jackson.databind.node.ObjectNode)
                                    json.readTree(Objects.toString(values.get("data_json"), "{}"));
                    data.put("_transportMissing", missing);
                    values.put("data_json", json.writeValueAsString(data));
                }
            }
            items.add(values);
        }
        enrichPlatformPayments(tenant, items);
        enrichSavedPlatformAllocations(tenant, items);
        enrichLegacyAttribution(tenant, items);
        return new Page(version, items);
    }

    /** 同运输详情取最新平台付款事实，独立于台账金额、分摊和状态。 */
    void enrichPlatformPayments(String tenant, List<Map<String, String>> items) {
        if (jdbc == null || items.isEmpty()) return;
        var args = new ArrayList<Object>();
        args.add(tenant);
        items.forEach(r -> args.add(r.get("id")));
        Map<String, String> payments =
                jdbc.query(
                        "SELECT cost_id,COALESCE(latest_source_json,source_json) AS payment_source"
                            + " FROM order_logistics_import_order WHERE tenant_id=? AND"
                            + " source_channel='API' AND cost_id IN ("
                            + String.join(",", Collections.nCopies(items.size(), "?"))
                            + ")",
                        rows -> {
                            Map<String, String> result = new HashMap<>();
                            while (rows.next()) {
                                var order =
                                        json.readValue(
                                                rows.getString("payment_source"), CarrierOrder.class);
                                result.merge(
                                        rows.getString("cost_id"),
                                        JdbcLogisticsImportStore.platformPaymentStatus(order),
                                        (first, second) -> first.equals(second) ? first : "NEEDS_REVIEW");
                            }
                            return result;
                        },
                        args.toArray());
        for (var row : items) {
            String payment = payments.get(row.get("id"));
            if (payment != null) row.put("payment_status_code", payment);
        }
    }

    /** 旧平台记录在确认前只存了业务关系；按保存金额投影，不能用最新平台账单重算历史成本。 */
    void enrichSavedPlatformAllocations(String tenant, List<Map<String, String>> items) {
        if (jdbc == null || items.isEmpty()) return;
        var candidates =
                items.stream()
                        .filter(
                                r ->
                                        "HUOLALA".equals(r.get("source_system"))
                                                && r.get("data_json") != null
                                                && r.get("sources_json") != null
                                                && !json.readTree(r.get("data_json"))
                                                        .path("ledger")
                                                        .isObject()
                                                && !json.readTree(r.get("sources_json")).isEmpty())
                        .toList();
        if (candidates.isEmpty()) return;
        var ids = candidates.stream().map(r -> r.get("id")).toList();
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(ids);
        var business = new HashMap<String, tools.jackson.databind.JsonNode>();
        for (var r :
                jdbc.queryForList(
                        "SELECT cost_id,business_json FROM order_logistics_import_order WHERE"
                                + " tenant_id=? AND business_json IS NOT NULL AND cost_id IN ("
                                + String.join(",", Collections.nCopies(ids.size(), "?"))
                                + ")",
                        args.toArray()))
            business.put(
                    r.get("cost_id").toString(), json.readTree(r.get("business_json").toString()));
        for (var row : candidates) {
            var draft = business.get(row.get("id"));
            if (draft == null || !"DELIVERY".equals(draft.path("transportType").asText())) continue;
            var links =
                    Arrays.asList(
                            json.readValue(
                                    row.get("sources_json"),
                                    com.rigour.order.api.v1.model.LogisticsCostSourceView[].class));
            if (links.isEmpty()
                    || links.stream()
                            .anyMatch(
                                    l ->
                                            l.documentNo() == null
                                                    || l.cityCode() == null
                                                    || l.cityCode().isBlank())) continue;
            var manual = new LinkedHashMap<String, java.math.BigDecimal>();
            if (draft.path("orderAllocations").isObject())
                draft.path("orderAllocations")
                        .properties()
                        .forEach(
                                e ->
                                        manual.put(
                                                e.getKey(),
                                                e.getValue().isNumber()
                                                        ? e.getValue().decimalValue()
                                                        : null));
            var total =
                    row.get("amount") == null ? null : new java.math.BigDecimal(row.get("amount"));
            com.rigour.order.application.service.cost.TransportAllocationPlan.Plan plan;
            try {
                plan =
                        com.rigour.order.application.service.cost.TransportAllocationPlan.calculate(
                                total, links, manual);
            } catch (com.rigour.shared.core.exception.BusinessException unresolved) {
                // 旧分摊已不守恒时保留整笔待归属，不猜测归属或阻断其他有效成本。
                continue;
            }
            var projected =
                    new ArrayList<com.rigour.order.api.v1.model.LogisticsCostLedger.Source>();
            for (int i = 0; i < links.size(); i++) {
                var l = links.get(i);
                projected.add(
                        new com.rigour.order.api.v1.model.LogisticsCostLedger.Source(
                                        l.sourceType(),
                                        l.documentNo(),
                                        "COST",
                                        List.of(
                                                new com.rigour.order.api.v1.model
                                                        .LogisticsCostLedger.Allocation(
                                                        "freight",
                                                        "TO" + i,
                                                        l.cityCode(),
                                                        plan.bases().get(l.documentNo()),
                                                        total == null
                                                                ? java.math.BigDecimal.ZERO
                                                                : plan.shares()
                                                                        .get(l.documentNo()))),
                                        null)
                                .attributed(l));
            }
            var body = (tools.jackson.databind.node.ObjectNode) json.readTree(row.get("data_json"));
            var ledger = body.putObject("ledger");
            ledger.put("schemaVersion", 2);
            ledger.put("transportPurposeCode", "DELIVERY");
            ledger.put("carrierCode", "HLL");
            ledger.put("deliveryModeCode", "THIRD_PARTY");
            ledger.put("costMonth", row.get("cost_date").substring(0, 7));
            var fee = ledger.putArray("feeItems").addObject();
            fee.put("feeKey", "freight");
            fee.put("feeTypeCode", "FREIGHT");
            fee.put("bearerCode", "COMPANY");
            fee.set("ourAmount", json.valueToTree(total));
            fee.set("feeAmount", json.valueToTree(total));
            row.put("data_json", json.writeValueAsString(body));
            row.put("sources_json", json.writeValueAsString(projected));
        }
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
