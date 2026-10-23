package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.PayrollComponentDefinition;

import tools.jackson.databind.node.ObjectNode;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** DEV fixtures only: align local HR dates and preserve historical commission order dates. */
public class AlignPayrollFixtureDates extends RebuildPayrollFixtures {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("preview", "apply").contains(args[0]))
            throw new IllegalArgumentException("preview|apply private-aligned-plan.json");
        var path = Path.of(args[1]).toAbsolutePath();
        if (path.startsWith(Path.of("").toAbsolutePath()))
            throw new IllegalArgumentException("Private plan required");
        var plan = JSON.readTree(Files.readString(path));
        tenant = plan.get("tenant").asText();
        var backup =
                path.resolveSibling(
                        path.getFileName().toString().replaceFirst("\\.json$", "")
                                + "-backup.json");
        try (var c = connect("hr")) {
            c.setAutoCommit(false);
            if (args[0].equals("apply")) {
                rows(
                        c,
                        "SELECT tenant_id FROM hr_payroll_guard WHERE tenant_id=? FOR UPDATE",
                        tenant);
            }
            var salaries =
                    rows(
                            c,
                            "SELECT * FROM hr_payroll_component_version WHERE tenant_id=? AND"
                                    + " reason=?",
                            tenant,
                            REASON);
            var assignments =
                    rows(
                            c,
                            "SELECT * FROM hr_payroll_rule WHERE tenant_id=? AND name LIKE"
                                    + " '验收测试%城市任职'",
                            tenant);
            if (args[0].equals("preview")) {
                Files.writeString(
                        backup,
                        JSON.writeValueAsString(
                                Map.of("components", salaries, "assignments", assignments)),
                        StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(backup, PosixFilePermissions.fromString("rw-------"));
                c.rollback();
                System.out.println(
                        "Fixture date backup saved; components="
                                + salaries.size()
                                + " assignments="
                                + assignments.size());
                return;
            }
            if (!Files.exists(backup))
                throw new IllegalStateException("Inspect preview before apply");
            if (!JSON.readTree(Files.readString(backup))
                    .equals(
                            JSON.valueToTree(
                                    Map.of("components", salaries, "assignments", assignments))))
                throw new IllegalStateException(
                        "Fixtures changed after preview; inspect a new backup");
            int changed = 0;
            for (var row : salaries) {
                var current = JSON.readTree(row.get("data_json").toString());
                if (!current.path("name").asText().startsWith("验收测试 · "))
                    throw new IllegalStateException("Not a fixture");
                String kind = current.path("kind").asText();
                if (!Set.of("SALARY", "COMMISSION").contains(kind)) continue;
                for (var desired : plan.get("definitions")) {
                    if (!kind.equals(desired.path("kind").asText())
                            || !Objects.equals(
                                    current.get("employeeCodes"), desired.get("employeeCodes")))
                        continue;
                    var checked = JSON.treeToValue(desired, PayrollComponentDefinition.class);
                    if (!PayrollSchemeValidator.component(checked).errors().isEmpty())
                        throw new IllegalStateException("Invalid target fixture");
                    var aligned = current.deepCopy();
                    if ("SALARY".equals(kind))
                        ((ObjectNode) aligned.get("salary"))
                                .set("personalPolicy", desired.get("salary").get("personalPolicy"));
                    else ((ObjectNode) aligned).set("effectiveFrom", desired.get("effectiveFrom"));
                    if (aligned.equals(current)) break;
                    if (update(
                                    c,
                                    "UPDATE hr_payroll_component_version SET"
                                        + " data_json=?,revision=revision+1 WHERE tenant_id=? AND"
                                        + " id=? AND revision=?",
                                    JSON.writeValueAsString(aligned),
                                    tenant,
                                    row.get("id"),
                                    row.get("revision"))
                            != 1) throw new IllegalStateException("Fixture revision conflict");
                    update(
                            c,
                            "INSERT INTO"
                                + " hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)"
                                + " VALUES(?,?,'TEST_DATE_ALIGNMENT',?,?,?)",
                            tenant,
                            row.get("id"),
                            ACTOR,
                            "DEV 测试参数按北京时间入职日对齐；提成保留真实历史订单取值日期",
                            JSON.writeValueAsString(row));
                    changed++;
                    break;
                }
            }
            for (var row : assignments) {
                var current = JSON.readTree(row.get("data_json").toString());
                for (var desired : plan.get("assignments")) {
                    if (!current.path("employeeCode")
                            .asText()
                            .equals(desired.path("employeeCode").asText())) continue;
                    if (current.path("effectiveFrom").equals(desired.path("effectiveFrom"))
                            && current.path("effectiveTo").equals(desired.path("effectiveTo")))
                        break;
                    var aligned = (ObjectNode) current.deepCopy();
                    aligned.set("effectiveFrom", desired.get("effectiveFrom"));
                    aligned.set("effectiveTo", desired.get("effectiveTo"));
                    if (update(
                                    c,
                                    "UPDATE hr_payroll_rule SET"
                                        + " effective_from=?,effective_to=?,data_json=?,revision=revision+1"
                                        + " WHERE tenant_id=? AND id=? AND revision=?",
                                    desired.get("effectiveFrom").asText(),
                                    desired.get("effectiveTo").asText(),
                                    JSON.writeValueAsString(aligned),
                                    tenant,
                                    row.get("id"),
                                    row.get("revision"))
                            != 1)
                        throw new IllegalStateException("Fixture assignment revision conflict");
                    update(
                            c,
                            "INSERT INTO"
                                + " hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)"
                                + " VALUES(?,?,'TEST_DATE_ALIGNMENT',?,?,?)",
                            tenant,
                            row.get("id"),
                            ACTOR,
                            "DEV 任职按真实员工北京时间日期对齐",
                            JSON.writeValueAsString(row));
                    changed++;
                    break;
                }
            }
            c.commit();
            System.out.println("Aligned explicitly labelled fixture rows=" + changed);
        }
    }
}
