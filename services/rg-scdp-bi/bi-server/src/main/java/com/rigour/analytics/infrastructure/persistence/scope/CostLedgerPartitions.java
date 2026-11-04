package com.rigour.analytics.infrastructure.persistence.scope;

import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.*;

/** 只展开已由来源服务确认的归属金额；关联单据数量不放大费用。 */
final class CostLedgerPartitions {
    private CostLedgerPartitions() {}
    static List<Map<String, String>> expand(String source, Map<String, String> row, ObjectMapper json) {
        if (!Set.of("ERP", "ORDER").contains(source) || row.get("data_json") == null) return List.of(row);
        JsonNode data = json.readTree(row.get("data_json"));
        if ("ERP".equals(source) && data.path("warehouseLedger").path("schemaVersion").asInt(0) == 2) return warehouse(row, (ObjectNode) data, json);
        if ("ORDER".equals(source) && data.path("ledger").path("schemaVersion").asInt(0) == 2) return logistics(row, (ObjectNode) data, json);
        return List.of(row);
    }
    private static List<Map<String, String>> warehouse(Map<String, String> row, ObjectNode data, ObjectMapper json) {
        SortedMap<String, BigDecimal> months = new TreeMap<>();
        Set<String> unknown = new HashSet<>();
        BigDecimal gross = BigDecimal.ZERO;
        boolean missing = false;
        for (var fee : data.path("warehouseLedger").path("feeItems")) {
            BigDecimal amount = money(fee.get("feeAmount"));
            Set<String> unique = new HashSet<>();
            BigDecimal allocated = BigDecimal.ZERO;
            boolean missingMonth = false;
            for (var m : fee.path("monthAllocations")) {
                String month = YearMonth.parse(m.path("month").asText()).toString();
                if (!unique.add(month)) throw new IllegalStateException("仓储来源月份重复");
                BigDecimal value = money(m.get("amount"));
                if (value == null) { unknown.add(month); missingMonth = true; }
                else { months.merge(month, value, BigDecimal::add); allocated = allocated.add(value); }
                months.putIfAbsent(month, BigDecimal.ZERO);
            }
            if (unique.isEmpty() || amount != null && (missingMonth || amount.compareTo(allocated) != 0)) throw new IllegalStateException("仓储来源分月金额不守恒");
            if (amount == null) missing = true; else gross = gross.add(amount);
        }
        checkTotal(row, gross, missing);
        List<Map<String, String>> result = new ArrayList<>();
        months.forEach((month, amount) -> result.add(partition(row, data, json, "MONTH:" + month, month, row.get("city_code"), unknown.contains(month) ? null : amount)));
        return result;
    }
    private static List<Map<String, String>> logistics(Map<String, String> row, ObjectNode data, ObjectMapper json) {
        var plan = data.path("ledger");
        String month = YearMonth.parse(plan.path("costMonth").asText()).toString();
        SortedMap<String, BigDecimal> cities = new TreeMap<>();
        Map<String, BigDecimal> expected = new HashMap<>(), allocated = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean missing = false;
        for (var fee : plan.path("feeItems")) {
            String key = fee.path("feeKey").asText();
            BigDecimal amount = money(fee.get("ourAmount"));
            if (expected.containsKey(key)) throw new IllegalStateException("物流来源费用项目重复");
            expected.put(key, amount);
            if (amount == null) missing = true; else total = total.add(amount);
        }
        JsonNode links = json.readTree(row.get("sources_json"));
        for (var link : links) {
            if (!"COST".equals(link.path("linkRoleCode").asText())) {
                if (!link.path("allocations").isEmpty()) throw new IllegalStateException("物流追溯单据重复计费");
                continue;
            }
            for (var a : link.path("allocations")) {
                String fee = a.path("feeKey").asText(), city = a.path("cityCode").asText();
                BigDecimal amount = money(a.get("amount"));
                if (!expected.containsKey(fee) || city.isBlank() || amount == null) throw new IllegalStateException("物流来源分摊无效");
                allocated.merge(fee, amount, BigDecimal::add);
                cities.merge(city, amount, BigDecimal::add);
            }
        }
        expected.forEach((key, amount) -> {
            BigDecimal value = allocated.getOrDefault(key, BigDecimal.ZERO);
            if (amount == null && value.signum() != 0 || amount != null && amount.compareTo(value) != 0) throw new IllegalStateException("物流来源分摊金额不守恒");
        });
        checkTotal(row, total, missing);
        if (cities.isEmpty()) cities.put(Objects.toString(row.get("city_code"), ""), BigDecimal.ZERO);
        List<Map<String, String>> result = new ArrayList<>();
        final boolean unknown = missing;
        cities.forEach((city, amount) -> result.add(partition(row, data, json, "CITY:" + city + ":" + month, month, city, unknown ? null : amount)));
        return result;
    }
    private static Map<String, String> partition(Map<String, String> row, ObjectNode original, ObjectMapper json, String key, String month, String city, BigDecimal amount) {
        Map<String, String> result = new LinkedHashMap<>(row);
        ObjectNode data = original.deepCopy();
        data.put("_ledgerRootId", row.get("id"));
        data.put("_ledgerPartition", key);
        data.put("costMonth", month);
        result.put("id", UUID.nameUUIDFromBytes((row.get("id") + ":" + key).getBytes(StandardCharsets.UTF_8)).toString());
        result.put("amount", amount == null ? null : amount.toPlainString());
        result.put("cost_date", month + "-01");
        result.put("city_code", city);
        result.put("data_json", json.writeValueAsString(data));
        return result;
    }
    private static BigDecimal money(JsonNode node) {
        if (node == null || node.isNull()) return null;
        BigDecimal amount = new BigDecimal(node.asText());
        if (amount.signum() < 0 || amount.scale() > 2) throw new IllegalStateException("费用来源金额格式无效");
        return amount;
    }
    private static void checkTotal(Map<String, String> row, BigDecimal amount, boolean unknown) {
        if (unknown && row.get("amount") != null || !unknown && (row.get("amount") == null || new BigDecimal(row.get("amount")).compareTo(amount) != 0)) throw new IllegalStateException("来源单据金额与归属明细不一致");
    }
}
