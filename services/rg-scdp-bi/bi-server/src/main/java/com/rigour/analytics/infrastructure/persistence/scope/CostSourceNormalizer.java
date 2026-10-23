package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.analytics.application.port.out.CostDashboardStore.City;
import com.rigour.analytics.domain.cost.CostFact;
import com.rigour.analytics.domain.cost.CostFact.Dimension;
import com.rigour.hr.api.v1.model.HrCostCityReference;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 只转换源台账事实；城市沿用经营城市部门/明确地区映射，冲突保留待归属。 */
@Component
public final class CostSourceNormalizer {
    private final ObjectMapper json;

    public CostSourceNormalizer(ObjectMapper json) {
        this.json = json;
    }

    public List<String> path(String value) {
        if (value == null || value.isBlank()) return List.of();
        JsonNode node = json.readTree(value);
        if (!node.isArray()) throw new IllegalStateException("部门路径格式无效");
        List<String> result = new ArrayList<>();
        for (var item : node) result.add(item.asText());
        return List.copyOf(result);
    }

    public List<City> cities(
            List<Map<String, String>> departments, List<Map<String, String>> areas) {
        Set<String> sales = new HashSet<>();
        departments.stream()
                .filter(this::active)
                .filter(d -> "销售部".equals(d.get("department_name")))
                .forEach(d -> sales.add(d.get("id")));
        List<City> result = new ArrayList<>();
        if (departments.stream()
                        .anyMatch(
                                d ->
                                        HrCostCityReference.HEADQUARTERS_CODE.equals(
                                                d.get("department_code")))
                || areas.stream()
                        .anyMatch(
                                a ->
                                        HrCostCityReference.HEADQUARTERS_CODE.equals(
                                                a.get("area_code"))))
            throw new IllegalStateException("总部成本归属保留编码与主档冲突");
        for (var d : departments) {
            if (!active(d)
                    || sales.contains(d.get("id"))
                    || Collections.disjoint(sales, path(d.get("department_path")))) continue;
            var matches =
                    areas.stream()
                            .filter(
                                    a ->
                                            !"1".equals(a.get("deleted"))
                                                    && "ACTIVE".equals(a.get("status")))
                            .filter(
                                    a ->
                                            normalizedName(a.get("area_name"))
                                                    .equals(
                                                            normalizedName(
                                                                    d.get("department_name"))))
                            .toList();
            result.add(
                    new City(
                            d.get("department_code"),
                            d.get("department_name"),
                            matches.size() == 1 ? matches.getFirst().get("area_code") : null,
                            d.get("id"),
                            path(d.get("department_path"))));
        }
        result.add(
                new City(
                        HrCostCityReference.HEADQUARTERS_CODE,
                        HrCostCityReference.HEADQUARTERS_NAME,
                        HrCostCityReference.HEADQUARTERS_CODE,
                        null,
                        List.of()));
        return result.stream().sorted(Comparator.comparing(City::code)).toList();
    }

    private boolean active(Map<String, String> d) {
        return !"1".equals(d.get("deleted")) && "ACTIVE".equals(d.get("status_code"));
    }

    private static String normalizedName(String s) {
        return Objects.toString(s, "").trim().replaceFirst("市$", "");
    }

    public CostFact normalize(
            String source,
            Map<String, String> r,
            List<City> cities,
            Map<String, List<String>> departments) {
        BigDecimal amount =
                r.get("amount") == null
                        ? null
                        : new BigDecimal(r.get("amount"))
                                .setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (amount != null && amount.signum() < 0) throw new IllegalStateException("成本源金额为负，拒绝发布");
        if (!Set.of("DRAFT", "CONFIRMED", "VOID").contains(r.get("status_code")))
            throw new IllegalStateException("成本源确认状态无效");
        if ("ORDER".equals(source) && !"COMPANY".equals(r.get("payer_code"))) return null;
        String category =
                switch (source) {
                    case "HR" -> "PAYROLL".equals(r.get("category_code")) ? "labor" : "expense";
                    case "ORDER" -> "freight";
                    case "ERP" ->
                            switch (Objects.toString(r.get("category_code"), "")) {
                                case "PURCHASE" -> "purchase";
                                case "LOSS" -> "loss";
                                case "MARKETING" -> "marketing";
                                case "RENTAL" ->
                                        switch (Objects.toString(r.get("premises_type"), "")) {
                                            case "WAREHOUSE" -> "warehouse";
                                            case "OFFICE" -> "office";
                                            default -> "premises_unknown";
                                        };
                                default -> throw new IllegalStateException("未知ERP成本类别");
                            };
                    default -> throw new IllegalStateException("未知成本来源");
                };
        JsonNode data = json.readTree(r.get("data_json"));
        if (!data.isObject()) throw new IllegalStateException("成本源业务快照无效");
        boolean expense = "HR".equals(source) && !"PAYROLL".equals(r.get("category_code"));
        String intended = text(data, "expense", "costCategory");
        if (expense && intended != null) {
            if (!Set.of("expense", "office", "warehouse", "freight", "marketing", "purchase")
                    .contains(intended)) throw new IllegalStateException("报销成本归类无效");
            category =
                    Set.of("TRAVEL", "ACCOMMODATION", "TAXI")
                                    .contains(Objects.toString(r.get("type_code"), ""))
                            ? "expense"
                            : intended;
        }
        String employee = r.get("HR".equals(source) ? "employee_code" : "owner_employee_code"),
                employeeName = r.get("HR".equals(source) ? "employee_name" : "owner_employee_name");
        String department = r.get("department_id");
        List<String> departmentPath =
                department == null ? List.of() : departments.getOrDefault(department, List.of());
        String rawCity = blank(r.get("city_code"));
        var codeMatches =
                cities.stream()
                        .filter(
                                c ->
                                        rawCity != null
                                                && (rawCity.equals(c.code())
                                                        || rawCity.equals(c.sourceRegion())))
                        .toList();
        var departmentMatches =
                cities.stream()
                        .filter(
                                c ->
                                        department != null
                                                && c.departmentId() != null
                                                && (department.equals(c.departmentId())
                                                        || departmentPath.contains(
                                                                c.departmentId())))
                        .toList();
        City city = null;
        String mapping = "MISSING";
        if (HrCostCityReference.HEADQUARTERS_CODE.equals(rawCity)) {
            city =
                    new City(
                            HrCostCityReference.HEADQUARTERS_CODE,
                            HrCostCityReference.HEADQUARTERS_NAME,
                            HrCostCityReference.HEADQUARTERS_CODE,
                            null,
                            List.of());
            mapping = "HEADQUARTERS";
        } else if (codeMatches.size() > 1
                || (departmentMatches.size() > 1
                        && !("HR".equals(source) && codeMatches.size() == 1)))
            mapping = "AMBIGUOUS";
        else if (!"HR".equals(source)
                && codeMatches.size() == 1
                && departmentMatches.size() == 1
                && !codeMatches.getFirst().code().equals(departmentMatches.getFirst().code()))
            mapping = "CONFLICT";
        else if (rawCity != null && codeMatches.isEmpty()) mapping = "UNMAPPED_CODE";
        else if (codeMatches.size() == 1) {
            city = codeMatches.getFirst();
            mapping = "CITY_CODE";
        } else if (departmentMatches.size() == 1) {
            city = departmentMatches.getFirst();
            mapping = "DEPARTMENT";
        }
        Map<String, Dimension> dims = new LinkedHashMap<>();
        String costMonth = text(data, "costMonth");
        boolean monthly =
                "PAYROLL".equals(r.get("category_code"))
                        || "RENTAL".equals(r.get("category_code"))
                        || expense
                                && Set.of("OFFICE_RENT", "WAREHOUSE_RENT", "NETWORK")
                                        .contains(Objects.toString(r.get("type_code"), ""));
        if ("PAYROLL".equals(r.get("category_code")))
            costMonth = r.get("cost_date").substring(0, 7);
        if (monthly && costMonth != null) {
            java.time.YearMonth.parse(costMonth);
            dims.put("costMonth", new Dimension(costMonth, costMonth, false));
            dims.put("periodBasis", new Dimension("MONTH", "按费用所属月", false));
        }

        if (expense) {
            String linkSource = text(data, "expense", "relatedCost", "source");
            String linkId = text(data, "expense", "relatedCost", "id");
            if (linkSource != null && linkId != null) {
                dims.put(
                        "relatedCost",
                        new Dimension(
                                linkSource + ":" + linkId,
                                Objects.toString(
                                        text(data, "expense", "relatedCost", "revision"), "-1"),
                                false));
                dims.put(
                        "relatedCategory",
                        new Dimension(
                                Objects.toString(
                                        text(data, "expense", "relatedCost", "category"), ""),
                                "原费用类别",
                                false));
                dims.put("costTreatment", new Dimension("REVIEW", "原费用待核对", false));
            }
            if ("FIXED_ASSET".equals(r.get("type_code")))
                dims.put("costTreatment", new Dimension("REVIEW", "资产支出待核对，不直接计入经营费用", false));
            dims.put("trip", dimension(null, text(data, "expense", "tripReference"), "trip"));
            dims.put("purpose", dimension(null, text(data, "expense", "purpose"), "purpose"));
            if ("COURIER".equals(r.get("type_code"))) {
                for (String field : List.of("contents", "recipientName", "companyName", "trackingNo")) {
                    String value = text(data, "expense", "courier", field);
                    if (value != null) dims.put("courier_" + field, dimension(null, value, "courier"));
                }
            }
        }
        if (city != null && city.departmentId() != null)
            dims.put(
                    "authorizationCityDepartment",
                    new Dimension(
                            city.departmentId(), String.join(",", city.departmentPath()), false));
        dims.put(
                "type",
                dimension(
                        "HR".equals(source)
                                        && !"PAYROLL".equals(r.get("category_code"))
                                        && r.get("type_code") != null
                                ? "REIMBURSEMENT_EXPENSE_TYPE:" + r.get("type_code")
                                : r.get("type_code"),
                        r.get("type_code"),
                        "code"));
        dims.put("employee", dimension(employee, employeeName, "employee"));
        dims.put(
                "department",
                dimension(
                        department,
                        Objects.toString(r.get("department_name"), department),
                        "department"));
        dims.put(
                "leg",
                dimension(
                        first(
                                text(data, "logistics", "legCode"),
                                text(data, "expense", "freightLeg")),
                        first(
                                text(data, "logistics", "legCode"),
                                text(data, "expense", "freightLeg")),
                        "code"));
        dims.put("carrier", dimension(null, text(data, "logistics", "carrierName"), "carrier"));
        String sender = text(data, "logistics", "senderName");
        String receiver = text(data, "logistics", "receiverName");
        dims.put(
                "route",
                dimension(
                        null,
                        sender == null || receiver == null ? null : sender + " → " + receiver,
                        "route"));
        dims.put(
                "warehouse",
                dimension(
                        r.get("warehouse_id"),
                        first(r.get("warehouse_name"), text(data, "goods", "location")),
                        "warehouse"));
        dims.put(
                "payee",
                dimension(
                        null,
                        first(text(data, "expense", "payeeName"), text(data, "payeeName")),
                        "payee"));
        dims.put(
                "destination",
                dimension(null, text(data, "expense", "destination"), "destination"));
        dims.put(
                "method",
                dimension(
                        text(data, "logistics", "transportMethodCode"),
                        text(data, "logistics", "transportMethodCode"),
                        "code"));
        dims.put(
                "premises",
                dimension(
                        r.get("warehouse_id"),
                        first(text(data, "rental", "premisesName"), r.get("warehouse_name")),
                        "warehouse"));
        dims.put("contract", dimension(null, text(data, "rental", "contractNo"), "contract"));
        dims.put("channel", dimension(null, text(data, "marketing", "channelName"), "channel"));
        dims.put(
                "supplier",
                dimension(
                        text(data, "goods", "supplierId"),
                        text(data, "goods", "supplierName"),
                        "supplier"));
        String product = first(text(data, "goods", "variantId"), text(data, "goods", "productId"));
        String productName =
                blank(
                        (Objects.toString(text(data, "goods", "productName"), "")
                                        + " "
                                        + Objects.toString(
                                                text(data, "goods", "specification"), ""))
                                .trim());
        dims.put(
                "product",
                dimension(
                        product,
                        productName,
                        text(data, "goods", "variantId") == null ? "product" : "variant"));
        dims.put("lossReason", dimension(null, text(data, "goods", "lossReason"), "reason"));
        if ("ERP".equals(source)) {
            if ("RENTAL".equals(r.get("category_code")))
                dims.put("recordArea", dimension(null, text(data, "rental", "area"), "area"));
            if (Set.of("PURCHASE", "LOSS").contains(Objects.toString(r.get("category_code"), ""))) {
                dims.put(
                        "recordUnitPrice",
                        dimension(null, text(data, "goods", "unitPrice"), "price"));
                dims.put(
                        "recordQuantity",
                        dimension(null, text(data, "goods", "quantity"), "quantity"));
                dims.put("recordUnit", dimension(null, text(data, "goods", "unit"), "unit"));
            }
        }
        Set<String> originals = new LinkedHashSet<>();
        // 关联业务单号由原模块单独鉴权；这里仅保留本费用自行登记的外部原始单号。
        add(originals, text(data, "goods", "externalDocument", "documentNo"));
        for (var row : data.path("externalDocuments")) add(originals, text(row, "documentNo"));
        for (var row : data.path("logistics").path("externalDocuments"))
            add(originals, text(row, "documentNo"));
        String date = r.get("cost_date");
        if (date == null) throw new IllegalStateException("费用发生日期缺失");
        return new CostFact(
                source,
                r.get("id"),
                switch (source) {
                    case "ERP" -> "erp_cost_entry";
                    case "ORDER" -> "order_logistics_cost";
                    default ->
                            "PAYROLL".equals(r.get("category_code")) ? "hr_payroll" : "hr_expense";
                },
                r.get("document_no"),
                r.get("title"),
                category,
                r.get("category_code"),
                city == null ? "UNKNOWN" : city.code(),
                city == null ? "待归属城市" : city.name(),
                rawCity,
                mapping,
                LocalDate.parse(date),
                amount,
                r.get("status_code"),
                Set.of("PAID", "UNPAID")
                                .contains(Objects.toString(r.get("payment_status_code"), ""))
                        ? r.get("payment_status_code")
                        : "UNKNOWN",
                "HR".equals(source) ? null : r.get("source_system"),
                employee,
                employeeName,
                department,
                departmentPath,
                r.get("warehouse_id"),
                r.get("source_warehouse_id"),
                r.get("created_by"),
                Long.parseLong(r.get("revision")),
                text(data, "remark"),
                Map.copyOf(dims),
                List.copyOf(originals),
                components(r));
    }

    private Map<String, BigDecimal> components(Map<String, String> row) {
        if (!"PAYROLL".equals(row.get("category_code"))) return Map.of();
        var node = json.readTree(row.get("components_json"));
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "baseSalary",
                        "performance",
                        "commission",
                        "allowance",
                        "correctionAmount")) {
            var value = node.path(key);
            if (value.isNumber()) values.put(key, new BigDecimal(value.asText()));
        }
        return values;
    }

    private static void add(Set<String> values, String s) {
        if (s != null) values.add(s);
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) n = n.path(k);
        return n.isMissingNode() || n.isNull() ? null : blank(n.asText());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String first(String a, String b) {
        return blank(a) != null ? a : b;
    }

    private static Dimension dimension(String id, String name, String kind) {
        if (blank(id) != null) return new Dimension(kind + ":" + id, first(name, id), false);
        if (blank(name) != null) return new Dimension("manual:" + kind + ":" + name, name, false);
        return new Dimension("UNKNOWN", "未记录", true);
    }
}
