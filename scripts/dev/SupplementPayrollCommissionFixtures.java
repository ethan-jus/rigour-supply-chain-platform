package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

/**
 * Explicit DEV test-rule supplement; preserves existing rules, salary inputs and payroll history.
 */
public final class SupplementPayrollCommissionFixtures extends RebuildPayrollFixtures {
    private static final String MARKER = "BEIJING_CLOTH_TEST_SUPPLEMENT_20261015";
    private static final String NOTE = "测试补充：ERP A500及台呢底布销售15元、城市总5元，按ERP套/床计提；不是已确认的正式标准";

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("preview", "apply").contains(args[0]))
            throw new IllegalArgumentException("Usage: preview|apply tenant-code YYYY-MM");
        var month = YearMonth.parse(args[2]);
        try (var c = connect("iam")) {
            c.setReadOnly(true);
            var found =
                    rows(
                            c,
                            "SELECT BIN_TO_UUID(id) id FROM iam_tenant WHERE tenant_code=?",
                            args[1]);
            if (found.size() != 1) throw new IllegalStateException("Ambiguous tenant");
            tenant = found.getFirst().get("id").toString();
        }
        List<Map<String, Object>> products;
        try (var c = connect("erp")) {
            c.setReadOnly(true);
            products =
                    rows(
                            c,
                            "SELECT p.product_name,v.id"
                                + " variant_id,v.specification_snapshot,v.unit_code FROM"
                                + " erp_product p JOIN erp_product_variant v ON"
                                + " p.tenant_id=v.tenant_id AND p.id=v.product_id WHERE"
                                + " p.tenant_id=? AND p.deleted=0 AND v.deleted=0 AND"
                                + " ((p.product_name='澳洋台呢 (华彩 中式)' AND"
                                + " v.specification_snapshot='A500' AND v.unit_code='SET') OR"
                                + " (p.product_name='澳洋台呢底布(华彩中式 )' AND v.unit_code='BED'))",
                            tenant);
            if (products.size() != 6
                    || products.stream().filter(p -> "SET".equals(p.get("unit_code"))).count() != 1)
                throw new IllegalStateException("Expected ERP A500 and five bottom-cloth variants");
        }
        try (var c = connect("hr")) {
            c.setAutoCommit(false);
            rows(c, "SELECT tenant_id FROM hr_payroll_guard WHERE tenant_id=? FOR UPDATE", tenant);
            if (!rows(
                            c,
                            "SELECT id FROM hr_payroll_component_version WHERE tenant_id=? AND"
                                + " request_id=?",
                            tenant,
                            MARKER)
                    .isEmpty()) {
                System.out.println("Already supplemented; no changes");
                c.rollback();
                return;
            }
            var locked =
                    rows(
                            c,
                            "SELECT id FROM hr_payroll WHERE tenant_id=? AND payroll_month>=? AND"
                                + " status_code IN ('CONFIRMED','PAID')",
                            tenant,
                            "2026-06");
            if (!locked.isEmpty())
                throw new IllegalStateException(
                        "Locked payroll exists; do not retrospectively supplement test rules");
            var people =
                    rows(
                            c,
                            "SELECT employee_name,department_id FROM hr_employee WHERE tenant_id=?"
                                + " AND deleted=0 AND employee_name IN ('马南','许少华') AND"
                                + " city_name='北京'",
                            tenant);
            if (people.size() != 2
                    || people.stream().map(p -> p.get("department_id")).distinct().count() != 1)
                throw new IllegalStateException(
                        "Beijing fixture employees must share one department");
            long department = ((Number) people.getFirst().get("department_id")).longValue();
            var versions =
                    rows(
                            c,
                            "SELECT id,rule_id,rule_code,version,data_json FROM"
                                + " hr_payroll_component_version WHERE tenant_id=? AND"
                                + " kind='COMMISSION' ORDER BY version DESC",
                            tenant);
            var candidates = new ArrayList<Map<String, Object>>();
            for (var row : versions) {
                var d =
                        JSON.readValue(
                                row.get("data_json").toString(), PayrollComponentDefinition.class);
                if (d.name().startsWith("测试 · 北京商品提成标准")
                        && d.departmentIds().equals(List.of(department))) candidates.add(row);
            }
            if (candidates.size() != 1)
                throw new IllegalStateException(
                        "Expected one original Beijing test commission version");
            var old = candidates.getFirst();
            var d =
                    JSON.readValue(
                            old.get("data_json").toString(), PayrollComponentDefinition.class);
            if (d.effectiveFrom().isAfter(month.atDay(1))
                    || d.effectiveTo().isBefore(month.atEndOfMonth()))
                throw new IllegalStateException("Fixture month outside rule period");
            var rules = new ArrayList<>(d.commission().rules());
            for (var product : products) {
                long sku = ((Number) product.get("variant_id")).longValue();
                if (rules.stream().anyMatch(r -> r.variantIds().contains(sku)))
                    throw new IllegalStateException(
                            "ERP variant already configured; preserve existing business standard");
                for (String role : List.of("SALES", "CITY_MANAGER")) {
                    String code = "TEST_CLOTH_" + sku + "_" + role;
                    rules.add(
                            new PayrollSchemeCommissionRule(
                                    code,
                                    "测试 · "
                                            + product.get("product_name")
                                            + " "
                                            + product.get("specification_snapshot"),
                                    role,
                                    List.of(),
                                    List.of(),
                                    List.of(sku),
                                    List.of(),
                                    "QUANTITY",
                                    new BigDecimal("SALES".equals(role) ? "15" : "5"),
                                    product.get("unit_code").toString(),
                                    false,
                                    "TEST_CLOTH_" + sku));
                }
            }
            var policy =
                    new PayrollSchemeCommissionPolicy(
                            d.commission().managerAttribution(),
                            d.commission().standardDate(),
                            rules);
            var definition =
                    new PayrollComponentDefinition(
                            d.name(),
                            d.kind(),
                            d.role(),
                            d.departmentIds(),
                            d.effectiveFrom(),
                            d.effectiveTo(),
                            d.salary(),
                            d.performance(),
                            policy,
                            d.calendar(),
                            d.allowance(),
                            d.employeeCodes());
            var validation = PayrollSchemeValidator.component(definition);
            if (!validation.errors().isEmpty())
                throw new IllegalStateException(validation.errors().toString());
            System.out.println(
                    "Existing standards retained: "
                            + d.commission().rules().size()
                            + "; test supplement: 6 ERP variants, 12 role rows. "
                            + NOTE);
            if ("preview".equals(args[0])) {
                c.rollback();
                return;
            }
            String versionId = id();
            int version = ((Number) old.get("version")).intValue() + 1;
            update(
                    c,
                    "INSERT INTO"
                        + " hr_payroll_component_version(id,tenant_id,request_id,rule_id,rule_code,version,kind,data_json,reason,created_by,created_time)"
                        + " VALUES(?,?,?,?,?,?,'COMMISSION',?,?,?,UTC_TIMESTAMP(6))",
                    versionId,
                    tenant,
                    MARKER,
                    old.get("rule_id"),
                    old.get("rule_code"),
                    version,
                    JSON.writeValueAsString(definition),
                    NOTE,
                    ACTOR);
            update(
                    c,
                    "INSERT INTO"
                        + " hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)"
                        + " VALUES(?,?,'TEST_RULE_SUPPLEMENTED',?,?,?)",
                    tenant,
                    versionId,
                    ACTOR,
                    NOTE,
                    JSON.writeValueAsString(
                            Map.of(
                                    "previousVersionId",
                                    old.get("id"),
                                    "newVersion",
                                    version,
                                    "addedVariants",
                                    products)));
            c.commit();
            System.out.println(
                    "Applied test commission version "
                            + version
                            + "; rule code preserved; recalculate draft payroll through normal HR"
                            + " workflow");
        }
    }
}
