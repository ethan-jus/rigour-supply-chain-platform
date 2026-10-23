package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/**
 * Explicit DEV acceptance tool. The plan and all HR snapshots stay outside the repository. preview
 * validates/backups; apply replaces only the exact old fixture IDs. Read-only verification is
 * performed separately through verify-payroll-api.py with the current user's gateway permissions.
 */
public class VerifyPayrollScenarios extends RebuildPayrollFixtures {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("preview", "apply").contains(args[0]))
            throw new IllegalArgumentException("Usage: preview|apply private-plan.json");
        Path planPath = Path.of(args[1]).toAbsolutePath();
        if (planPath.startsWith(Path.of("").toAbsolutePath()))
            throw new IllegalArgumentException("HR plans must remain outside the Git workspace");
        var plan = JSON.readTree(Files.readString(planPath));
        tenant = plan.get("tenant").asText();
        var definitions = new ArrayList<PayrollComponentDefinition>();
        for (var row : plan.get("definitions")) {
            var definition = JSON.treeToValue(row, PayrollComponentDefinition.class);
            var check = PayrollSchemeValidator.component(definition);
            if (!check.errors().isEmpty())
                throw new IllegalStateException(definition.name() + ": " + check.errors());
            if (!definition.name().startsWith("验收测试 · "))
                throw new IllegalArgumentException("Unlabelled fixture");
            definitions.add(definition);
        }
        Path backup = planPath.resolveSibling("fixture-backup.json");
        var tables =
                List.of(
                        "hr_employee",
                        "hr_employee_profile",
                        "hr_payroll",
                        "hr_payroll_input",
                        "hr_payroll_payment",
                        "hr_payroll_commission_release",
                        "hr_payroll_correction",
                        "hr_payroll_component_version",
                        "hr_payroll_component_operation",
                        "hr_payroll_component_review",
                        "hr_payroll_rule",
                        "hr_payroll_rule_department",
                        "hr_payroll_scheme_month_input",
                        "hr_business_target",
                        "hr_payroll_configuration");
        if (args[0].equals("preview")) {
            try (var c = connect("hr")) {
                c.setReadOnly(true);
                c.setAutoCommit(false);
                var data = new LinkedHashMap<String, Object>();
                for (String table : tables) {
                    String fields =
                            table.equals("hr_employee_profile")
                                    ? "employee_id,tenant_id,probation_salary,regular_salary"
                                    : "*";
                    data.put(
                            table,
                            rows(
                                    c,
                                    "SELECT " + fields + " FROM " + table + " WHERE tenant_id=?",
                                    tenant));
                }
                c.rollback();
                Files.writeString(
                        backup, JSON.writeValueAsString(data), StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(backup, PosixFilePermissions.fromString("rw-------"));
                System.out.println(
                        "Private backup saved; validated fixture components=" + definitions.size());
            }
            return;
        }
        if (args[0].equals("apply")) {
            if (!Files.exists(backup))
                throw new IllegalStateException("Run preview and inspect backup first");
            try (var c = connect("hr")) {
                c.setAutoCommit(false);
                rows(
                        c,
                        "SELECT tenant_id FROM hr_payroll_guard WHERE tenant_id=? FOR UPDATE",
                        tenant);
                Set<String> expected = new HashSet<>();
                for (var row : plan.get("oldComponents")) {
                    if (!JSON.readTree(row.get("data_json").asText())
                            .get("name")
                            .asText()
                            .startsWith("测试")) throw new IllegalStateException("Non-test old rule");
                    expected.add(row.get("id").asText());
                }
                Set<String> actual = new HashSet<>();
                for (var row :
                        rows(
                                c,
                                "SELECT id FROM hr_payroll_component_version WHERE tenant_id=?",
                                tenant)) actual.add(row.get("id").toString());
                if (!expected.equals(actual))
                    throw new IllegalStateException("Rules changed since audit; aborting");
                for (var row : plan.get("oldPayroll")) {
                    String id = row.get("id").asText();
                    var saved =
                            rows(
                                    c,
                                    "SELECT calculation_json,revision FROM hr_payroll WHERE"
                                            + " tenant_id=? AND id=?",
                                    tenant,
                                    id);
                    if (saved.size() != 1
                            || ((Number) saved.getFirst().get("revision")).intValue()
                                    != row.get("revision").asInt())
                        throw new IllegalStateException("Payroll changed since audit");
                    var calculation =
                            JSON.readTree(saved.getFirst().get("calculation_json").toString());
                    var refs =
                            calculation
                                    .path("scheme")
                                    .path("scheme")
                                    .path("definition")
                                    .path("ruleReferences");
                    if (!expected.contains(refs.path("salaryId").asText()))
                        throw new IllegalStateException("Non-test salary snapshot");
                    for (String table :
                            List.of("hr_payroll_payment", "hr_payroll_commission_release"))
                        update(
                                c,
                                "DELETE FROM " + table + " WHERE tenant_id=? AND payroll_id=?",
                                tenant,
                                id);
                    if (!rows(
                                    c,
                                    "SELECT id FROM hr_payroll_correction WHERE tenant_id=? AND"
                                            + " source_payroll_id=?",
                                    tenant,
                                    id)
                            .isEmpty())
                        throw new IllegalStateException(
                                "Existing correction requires manual inspection");
                    update(c, "DELETE FROM hr_payroll WHERE tenant_id=? AND id=?", tenant, id);
                }
                for (String id : expected) {
                    update(
                            c,
                            "DELETE FROM hr_payroll_component_review WHERE tenant_id=? AND rule_id"
                                    + " IN (SELECT rule_id FROM hr_payroll_component_version WHERE"
                                    + " tenant_id=? AND id=?)",
                            tenant,
                            tenant,
                            id);
                    update(
                            c,
                            "DELETE FROM hr_payroll_component_operation WHERE tenant_id=? AND"
                                    + " version_id=?",
                            tenant,
                            id);
                    update(
                            c,
                            "DELETE FROM hr_payroll_component_version WHERE tenant_id=? AND id=?",
                            tenant,
                            id);
                }
                for (var row : plan.get("oldAssignments")) {
                    if (!row.get("name").asText().startsWith("测试"))
                        throw new IllegalStateException("Non-test assignment");
                    update(
                            c,
                            "DELETE FROM hr_payroll_rule_department WHERE tenant_id=? AND"
                                    + " rule_id=?",
                            tenant,
                            row.get("id").asText());
                    update(
                            c,
                            "DELETE FROM hr_payroll_rule WHERE tenant_id=? AND id=? AND revision=?",
                            tenant,
                            row.get("id").asText(),
                            row.get("revision").asInt());
                }
                update(
                        c,
                        "DELETE FROM hr_business_target WHERE tenant_id=? AND"
                                + " metric_code='PAYROLL_SCOPE_FULLY_PAID' AND remark=?",
                        tenant,
                        REASON);
                // Historical audit records and real attendance / orders / receipts are preserved.
                for (var definition : definitions) component(c, definition);
                update(
                        c,
                        "INSERT INTO"
                            + " hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)"
                            + " VALUES(?,?,'TEST_SCENARIOS_REBUILT',?,?,?)",
                        tenant,
                        id(),
                        ACTOR,
                        plan.get("reason").asText(),
                        JSON.writeValueAsString(
                                Map.of(
                                        "backupFile",
                                        backup.getFileName().toString(),
                                        "oldFixtureCount",
                                        expected.size(),
                                        "newFixtureCount",
                                        definitions.size())));
                c.commit();
                System.out.println(
                        "Exact old fixtures replaced; no employee/payroll business facts changed by"
                                + " cleanup");
            }
        }
    }
}
