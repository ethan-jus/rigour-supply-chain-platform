package com.rigour.hr.application.service;

import com.rigour.hr.HrApplication;
import com.rigour.hr.api.v1.model.*;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.yaml.snakeyaml.Yaml;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit DEV-only maintenance tool. Never invoked by service startup or regular tests. */
public class RebuildPayrollFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ACTOR = "cd9627ee-b1cf-423b-93d4-31906531c005";
    static final String REASON = "测试数据重建；金额与日历仅用于验收，不是正式薪资标准";
    static String tenant;

    static Connection connect(String domain) throws Exception {
        var path =
                Path.of(
                        "services/rg-scdp-"
                                + domain
                                + "/"
                                + domain
                                + "-server/src/main/resources/application-dev.yml");
        Map<?, ?> yaml = new Yaml().load(Files.readString(path));
        var ds = (Map<?, ?>) ((Map<?, ?>) yaml.get("spring")).get("datasource");
        String url = ds.get("url").toString();
        if (!url.contains("192.168.12.7:13306/rigour_"))
            throw new IllegalStateException(
                    "Only the explicitly approved shared DEV endpoint is supported");
        return DriverManager.getConnection(
                url, ds.get("username").toString(), ds.get("password").toString());
    }

    static List<Map<String, Object>> rows(Connection c, String sql, Object... args)
            throws Exception {
        try (var st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
            try (var rs = st.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                var md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        if (value instanceof java.sql.Date || value instanceof Timestamp)
                            value = value.toString();
                        row.put(md.getColumnLabel(i), value);
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    static int update(Connection c, String sql, Object... args) throws Exception {
        try (var st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
            return st.executeUpdate();
        }
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static long category(List<Map<String, Object>> categories, String name, Long parent) {
        var found =
                categories.stream()
                        .filter(
                                r ->
                                        name.equals(r.get("category_name"))
                                                && (parent == null
                                                        || Objects.equals(
                                                                ((Number) r.get("parent_id"))
                                                                        .longValue(),
                                                                parent)))
                        .toList();
        if (found.size() != 1)
            throw new IllegalStateException("ERP category is missing or ambiguous: " + name);
        return ((Number) found.getFirst().get("id")).longValue();
    }

    static PayrollSchemeCommissionRule commission(
            String code, String name, String role, long category, Long exclude, String value) {
        return new PayrollSchemeCommissionRule(
                code,
                name,
                role,
                List.of(category),
                exclude == null ? List.of() : List.of(exclude),
                List.of(),
                List.of(),
                "PERCENT",
                new BigDecimal(value),
                "",
                false);
    }

    static String component(Connection c, PayrollComponentDefinition definition) throws Exception {
        var check = PayrollSchemeValidator.component(definition);
        if (!check.errors().isEmpty()) throw new IllegalStateException(check.errors().toString());
        String id = id();
        update(
                c,
                "INSERT INTO"
                    + " hr_payroll_component_version(id,tenant_id,request_id,rule_id,rule_code,version,kind,data_json,reason,created_by,created_time)"
                    + " VALUES(?,?,?,?,?,1,?,?,?,?,UTC_TIMESTAMP(6))",
                id,
                tenant,
                id,
                id,
                new BusinessCodeGenerator()
                        .generateUnique(
                                BusinessCodeRule.daily("PCR", 6),
                                code -> {
                                    try {
                                        return rows(
                                                        c,
                                                        "SELECT 1 FROM hr_payroll_component_version"
                                                            + " WHERE tenant_id=? AND rule_code=?",
                                                        tenant,
                                                        code)
                                                .isEmpty();
                                    } catch (Exception error) {
                                        throw new IllegalStateException(error);
                                    }
                                }),
                definition.kind(),
                JSON.writeValueAsString(definition),
                REASON,
                ACTOR);
        return id;
    }

    static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("preview", "apply", "generate").contains(args[0]))
            throw new IllegalArgumentException("Usage: preview|apply|generate tenant-code YYYY-MM");
        var month = YearMonth.parse(args[2]);
        if (month.isAfter(YearMonth.now(ZoneId.of("Asia/Shanghai"))))
            throw new IllegalArgumentException("Cannot seed future payroll");
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
        List<Map<String, Object>> people;
        try (var c = connect("hr")) {
            c.setReadOnly(true);
            people =
                    rows(
                            c,
                            "SELECT employee_code,employee_name,department_id,city_name FROM"
                                + " hr_employee WHERE tenant_id=? AND deleted=0 AND employee_name"
                                + " IN ('马南','许少华')",
                            tenant);
        }
        if (people.size() != 2 || people.stream().anyMatch(v -> !"北京".equals(v.get("city_name"))))
            throw new IllegalStateException(
                    "The two Beijing employees must be uniquely identified");
        String manager =
                people.stream()
                        .filter(v -> "马南".equals(v.get("employee_name")))
                        .findFirst()
                        .orElseThrow()
                        .get("employee_code")
                        .toString();
        String salesperson =
                people.stream()
                        .filter(v -> "许少华".equals(v.get("employee_name")))
                        .findFirst()
                        .orElseThrow()
                        .get("employee_code")
                        .toString();
        var departments =
                people.stream()
                        .map(v -> ((Number) v.get("department_id")).longValue())
                        .distinct()
                        .toList();
        if (departments.size() != 1)
            throw new IllegalStateException("Expected one Beijing department");
        String city;
        try (var c = connect("order")) {
            c.setReadOnly(true);
            var cities =
                    rows(
                            c,
                            "SELECT DISTINCT region_code FROM order_sales_order WHERE tenant_id=?"
                                + " AND owner_employee_code=? AND deleted=0",
                            tenant,
                            salesperson);
            if (cities.size() != 1 || cities.getFirst().get("region_code") == null)
                throw new IllegalStateException("Order city is ambiguous");
            city = cities.getFirst().get("region_code").toString();
        }
        var from = month.atDay(1);
        var end = LocalDate.of(9999, 12, 31);
        var template =
                JSON.readValue(
                        Files.readString(
                                Path.of(
                                        "services/rg-scdp-hr/hr-server/src/test/resources/payroll/scheme.json")),
                        PayrollSchemeDefinition.class);
        List<PayrollSchemeCommissionRule> productRules = new ArrayList<>();
        try (var c = connect("erp")) {
            c.setReadOnly(true);
            var categories =
                    rows(
                            c,
                            "SELECT id,parent_id,category_name FROM erp_product_category WHERE"
                                + " tenant_id=? AND deleted=0",
                            tenant);
            long noodles = category(categories, "方便面", category(categories, "快销品", null));
            long billiards = category(categories, "台球周边", null),
                    cloth = category(categories, "台呢", billiards);
            productRules.add(commission("NOODLES_SALES", "方便面", "SALES", noodles, null, "4"));
            productRules.add(
                    commission("NOODLES_MANAGER", "方便面", "CITY_MANAGER", noodles, null, "1"));
            productRules.add(
                    commission("BILLIARDS_SALES", "台球周边（排除台呢）", "SALES", billiards, cloth, "10"));
            productRules.add(
                    commission(
                            "BILLIARDS_MANAGER",
                            "台球周边（排除台呢）",
                            "CITY_MANAGER",
                            billiards,
                            cloth,
                            "2"));
            // Only exact ERP product/model/unit matches use the supplied quantity standards.
            // A500B is not silently mapped to ERP A500; bed cloth is not a set.
            for (String model : List.of("A300", "A600", "A700", "A800")) {
                var variants =
                        rows(
                                c,
                                "SELECT v.id FROM erp_product p JOIN erp_product_variant v ON"
                                    + " p.tenant_id=v.tenant_id AND p.id=v.product_id WHERE"
                                    + " p.tenant_id=? AND p.deleted=0 AND v.deleted=0 AND"
                                    + " p.product_name='澳洋台呢 (华彩 中式)' AND"
                                    + " v.specification_snapshot=? AND v.unit_code='SET'",
                                tenant,
                                model);
                if (variants.size() != 1)
                    throw new IllegalStateException("ERP model missing or ambiguous: " + model);
                long variant = ((Number) variants.getFirst().get("id")).longValue();
                for (String role : List.of("SALES", "CITY_MANAGER")) {
                    var rate =
                            n(
                                    "A300".equals(model)
                                            ? ("SALES".equals(role) ? "15" : "5")
                                            : ("SALES".equals(role) ? "25" : "10"));
                    productRules.add(
                            new PayrollSchemeCommissionRule(
                                    "CLOTH_" + model + "_" + role,
                                    "澳洋华彩（中式）" + model,
                                    role,
                                    List.of(),
                                    List.of(),
                                    List.of(variant),
                                    List.of(),
                                    "QUANTITY",
                                    rate,
                                    "SET",
                                    false,
                                    "CLOTH_" + model));
                }
            }
        }
        var commission =
                new PayrollSchemeCommissionPolicy("ORDER_DATE", "ORDER_DATE", productRules);
        var calendar =
                new PayrollWorkCalendar(
                        List.of(1, 2, 3, 4, 5),
                        List.of(),
                        List.of(),
                        List.of(),
                        new PayrollWorkWindow(
                                LocalTime.of(9, 0), LocalTime.of(6, 0), true, "IGNORE"),
                        List.of(),
                        null);
        var definitions = new ArrayList<PayrollComponentDefinition>();
        definitions.add(
                new PayrollComponentDefinition(
                        "测试 · 北京薪资考勤规则",
                        "ATTENDANCE",
                        "ALL",
                        departments,
                        from,
                        end,
                        null,
                        null,
                        null,
                        calendar));
        definitions.add(
                new PayrollComponentDefinition(
                        "测试 · 北京商品提成标准",
                        "COMMISSION",
                        "ALL",
                        departments,
                        LocalDate.of(2026, 6, 1),
                        end,
                        null,
                        null,
                        commission,
                        null));
        for (var person : people) {
            boolean sales = person.get("employee_code").equals(salesperson);
            String role = sales ? "SALES" : "CITY_MANAGER", label = sales ? "销售" : "城市总";
            var grades =
                    template.baseSalary().tiers().stream()
                            .map(
                                    t ->
                                            new PayrollGradeBand(
                                                    t.code(),
                                                    t.name(),
                                                    t.lower(),
                                                    t.upper(),
                                                    t.lowerInclusive(),
                                                    t.upperInclusive()))
                            .toList();
            var metric =
                    new PayrollMetricDefinition(
                            "PAYROLL_SCOPE_FULLY_PAID",
                            "管辖城市整单付清回款",
                            "FULLY_PAID_RECEIPTS",
                            null,
                            n("100"),
                            List.of(),
                            null);
            var policy =
                    new PayrollPerformanceStandard(
                            sales ? "SALES" : "MANAGER",
                            sales ? template.sales() : null,
                            sales
                                    ? null
                                    : new PayrollManagerPolicy(
                                            List.of(metric),
                                            new PayrollProtectionPolicy("MONTHS", 1, true),
                                            n("1.2"),
                                            template.manager().steps()),
                            sales ? grades : List.of());
            definitions.add(
                    new PayrollComponentDefinition(
                            "测试 · 北京" + label + "绩效规则",
                            "PERFORMANCE",
                            role,
                            departments,
                            from,
                            end,
                            null,
                            policy,
                            null,
                            null,
                            null,
                            List.of()));
            definitions.add(
                    new PayrollComponentDefinition(
                            "测试 · 北京" + label + "补贴规则",
                            "ALLOWANCE",
                            role,
                            departments,
                            from,
                            end,
                            null,
                            null,
                            null,
                            null,
                            new PayrollAllowanceStandard(
                                    sales ? template.allowances() : List.of(), "NONE"),
                            List.of()));
            var salary =
                    new PayrollSalaryStandard(
                            sales
                                    ? template.baseSalary()
                                    : new PayrollSchemeBaseSalary(
                                            "FIXED", n("6000"), List.of(), null),
                            sales ? null : n("2000"),
                            new PayrollProrationPolicy("NONE", "NONE", "NONE"));
            definitions.add(
                    new PayrollComponentDefinition(
                            "测试 · " + person.get("employee_name") + "薪资标准",
                            "SALARY",
                            role,
                            List.of(),
                            from,
                            end,
                            salary,
                            null,
                            null,
                            null,
                            null,
                            List.of(person.get("employee_code").toString())));
        }
        for (var d : definitions) {
            var check = PayrollSchemeValidator.component(d);
            if (!check.errors().isEmpty())
                throw new IllegalStateException(d.name() + ":" + check.errors());
        }
        if (!args[0].equals("generate")) {
            try (var c = connect("hr")) {
                c.setAutoCommit(false);
                update(c, "INSERT IGNORE INTO hr_payroll_guard(tenant_id) VALUES(?)", tenant);
                rows(
                        c,
                        "SELECT tenant_id FROM hr_payroll_guard WHERE tenant_id=? FOR UPDATE",
                        tenant);
                var old =
                        rows(
                                c,
                                "SELECT payroll_month,status_code,count(*) count FROM hr_payroll"
                                    + " WHERE tenant_id=? GROUP BY payroll_month,status_code",
                                tenant);
                if (old.stream()
                        .anyMatch(v -> Set.of("CONFIRMED", "PAID").contains(v.get("status_code"))))
                    throw new IllegalStateException(
                            "Confirmed/paid payroll appeared; this fixture tool only resets"
                                + " drafts");
                System.out.println("Existing draft payroll: " + JSON.writeValueAsString(old));
                System.out.println(
                        "Beijing fixture: 2 employees, 8 rule records; sales B/P"
                            + " 3000/1000,4000/1500,5000/2000; manager B/P 6000/2000; target 20000."
                            + " All monetary standards are TEST values.");
                if (args[0].equals("preview")) {
                    c.rollback();
                    return;
                }
                // User explicitly authorized clearing old erroneous test rules/results without
                // backup.
                for (String table :
                        List.of(
                                "hr_payroll_payment",
                                "hr_payroll_commission_release",
                                "hr_payroll_correction",
                                "hr_payroll_input",
                                "hr_payroll_scheme_month_input",
                                "hr_payroll",
                                "hr_payroll_scheme_event",
                                "hr_payroll_scheme_version",
                                "hr_payroll_component_version",
                                "hr_payroll_rule_department",
                                "hr_payroll_rule",
                                "hr_attendance_rule",
                                "hr_payroll_configuration"))
                    update(c, "DELETE FROM " + table + " WHERE tenant_id=?", tenant);
                for (var d : definitions) component(c, d);
                var assignment =
                        JSON.readValue(
                                JSON.writeValueAsString(
                                        Map.ofEntries(
                                                Map.entry("requestId", id()),
                                                Map.entry("name", "测试 · 北京城市总—马南"),
                                                Map.entry("kindCode", "CITY_MANAGER"),
                                                Map.entry("employeeCode", manager),
                                                Map.entry("departmentIds", List.of()),
                                                Map.entry("cityCode", city),
                                                Map.entry("cityName", "北京市"),
                                                Map.entry("includeOwnSalesCommission", true),
                                                Map.entry("effectiveFrom", "2026-06-24"),
                                                Map.entry("effectiveTo", "9999-12-31"),
                                                Map.entry("enabled", true),
                                                Map.entry("remark", REASON))),
                                PayrollRuleCommand.class);
                update(
                        c,
                        "INSERT INTO"
                            + " hr_payroll_rule(id,tenant_id,request_id,kind_code,name,employee_code,effective_from,effective_to,enabled,data_json,created_by,updated_by)"
                            + " VALUES(?,?,?,'CITY_MANAGER',?,?,?,?,true,?,?,?)",
                        id(),
                        tenant,
                        assignment.requestId(),
                        assignment.name(),
                        manager,
                        assignment.effectiveFrom(),
                        end,
                        JSON.writeValueAsString(assignment),
                        ACTOR,
                        ACTOR);
                update(
                        c,
                        "UPDATE hr_employee SET payroll_category='SALES',revision=revision+1 WHERE"
                            + " tenant_id=? AND employee_code=? AND payroll_category IS NULL",
                        tenant,
                        salesperson);
                update(
                        c,
                        "DELETE FROM hr_business_target WHERE tenant_id=? AND"
                            + " metric_code='PAYROLL_SCOPE_FULLY_PAID' AND remark=?",
                        tenant,
                        REASON);
                update(
                        c,
                        "INSERT INTO"
                            + " hr_business_target(tenant_id,target_month,dimension_type,dimension_code,dimension_name,metric_code,target_value,remark,revision,created_by,updated_by)"
                            + " VALUES(?,?,'SALES_OWNER',?,'马南','PAYROLL_SCOPE_FULLY_PAID',20000,?,1,?,?)",
                        tenant,
                        from,
                        manager,
                        REASON,
                        ACTOR,
                        ACTOR);
                update(
                        c,
                        "INSERT INTO"
                            + " hr_payroll_configuration(tenant_id,unified_from,reason,updated_by,updated_time)"
                            + " VALUES(?,?,?,?,UTC_TIMESTAMP(6))",
                        tenant,
                        args[2],
                        REASON,
                        ACTOR);
                update(
                        c,
                        "INSERT INTO"
                            + " hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)"
                            + " VALUES(?,?,'TEST_DATA_REBUILT',?,?,?)",
                        tenant,
                        id(),
                        ACTOR,
                        REASON,
                        JSON.writeValueAsString(
                                Map.of(
                                        "employees",
                                        List.of(manager, salesperson),
                                        "newComponents",
                                        definitions.size(),
                                        "backupRequested",
                                        false)));
                c.commit();
                System.out.println(
                        "Rebuilt Beijing test rules without backup. Original orders, receipts,"
                            + " employees and check-in facts retained.");
            }
        }
        if (args[0].equals("apply")) return;
        try (var context =
                new SpringApplicationBuilder(HrApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .run(
                                "--spring.profiles.active=dev,local",
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--spring.cloud.nacos.discovery.register-enabled=false",
                                "--spring.jmx.enabled=false")) {
            TestAuthorizationContext.set(
                    new CallerIdentity(
                            "SERVICE",
                            UUID.fromString(ACTOR),
                            UUID.fromString(tenant),
                            null,
                            null,
                            UUID.randomUUID(),
                            0,
                            0,
                            0,
                            Set.of(),
                            Set.of("*:*:*")));
            try {
                var service = context.getBean(HrPayrollService.class);
                for (String code : List.of(salesperson, manager)) {
                    var result =
                            service.calculate(new PayrollCalculateCommand(args[2], List.of(code)))
                                    .getFirst();
                    System.out.println("PAYROLL_RESULT " + JSON.writeValueAsString(result));
                }
            } finally {
                TestAuthorizationContext.clear();
            }
        }
    }
}
