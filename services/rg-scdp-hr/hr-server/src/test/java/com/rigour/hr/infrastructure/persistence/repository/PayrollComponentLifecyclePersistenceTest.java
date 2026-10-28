package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.infrastructure.persistence.entity.*;
import com.rigour.hr.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.*;
import java.util.*;

class PayrollComponentLifecyclePersistenceTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusHrPayrollComponentLifecycleRepository store;
    MybatisPlusHrPayrollComponentRepository catalog;
    PayrollComponentVersionView rule;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:rule_lifecycle_" + UUID.randomUUID() + ";MODE=MySQL",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        execute("V21__hr_payroll_component_catalog.sql");
        jdbc.execute("ALTER TABLE hr_payroll_component_version ADD COLUMN rule_code VARCHAR(40)");
        jdbc.execute(
                "CREATE TABLE hr_attendance_note(tenant_id VARCHAR(64), attendance_month CHAR(7),"
                    + " employee_code VARCHAR(50), reason VARCHAR(500), revision INT, updated_by"
                    + " VARCHAR(50), updated_time TIMESTAMP, actual_days_override DECIMAL(8,2),"
                    + " absent_days_override DECIMAL(8,2), leave_type_code VARCHAR(64),"
                    + " attendance_status VARCHAR(20), confirmed_snapshot_json CLOB,"
                    + " confirmed_department_id BIGINT, confirmed_rule_department_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE hr_payroll(id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(64),"
                    + " employee_code VARCHAR(50), employee_name VARCHAR(120), department_id"
                    + " BIGINT, department_name VARCHAR(120), city_name VARCHAR(120), payroll_month"
                    + " CHAR(7), status_code VARCHAR(20), revision INT, calculation_json CLOB,"
                    + " created_by VARCHAR(50), updated_by VARCHAR(50), created_time TIMESTAMP,"
                    + " updated_time TIMESTAMP)");
        execute("V24__payroll_rule_lifecycle.sql");
        execute("V27__attendance_assessment_override.sql");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (var mapper :
                List.of(
                        HrPayrollComponentMapper.class,
                        HrPayrollComponentOperationMapper.class,
                        HrPayrollComponentReviewMapper.class,
                        HrPayrollMapper.class,
                        HrPayrollSchemeMapper.class,
                        HrAttendanceNoteMapper.class,
                        HrPayrollConfigurationMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var raw =
                spy(
                        new MybatisPlusHrPayrollComponentLifecycleRepository(
                                session.getMapper(HrPayrollComponentMapper.class),
                                        session.getMapper(HrPayrollComponentOperationMapper.class),
                                session.getMapper(HrPayrollComponentReviewMapper.class),
                                        session.getMapper(HrPayrollMapper.class),
                                session.getMapper(HrPayrollSchemeMapper.class),
                                        session.getMapper(HrAttendanceNoteMapper.class),
                                mock(HrPayrollPersistenceSupport.class), json));
        doReturn(false).when(raw).inUse(anyString(), anyString());
        var proxy = new ProxyFactory(raw);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        store = (MybatisPlusHrPayrollComponentLifecycleRepository) proxy.getProxy();
        catalog =
                new MybatisPlusHrPayrollComponentRepository(
                        session.getMapper(HrPayrollComponentMapper.class),
                        session.getMapper(HrPayrollConfigurationMapper.class),
                        mock(HrPayrollPersistenceSupport.class),
                        json,
                        raw);
        var definition =
                new PayrollComponentDefinition(
                        "销售考勤",
                        "ATTENDANCE",
                        "ALL",
                        List.of(1L),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(9999, 12, 31),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of());
        rule =
                new PayrollComponentVersionView(
                        "V1", "R1", 1, definition, "创建", "HR", Instant.EPOCH, "PCR001");
        jdbc.update(
                "INSERT INTO"
                    + " hr_payroll_component_version(id,tenant_id,request_id,rule_id,version,kind,data_json,reason,created_by,created_time,rule_code)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                rule.id(),
                "T1",
                "create",
                rule.ruleId(),
                1,
                "ATTENDANCE",
                json.writeValueAsString(definition),
                "创建",
                "HR",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                "PCR001");
    }

    @Test
    void postProbationPercentageSurvivesSalarySaveAndTenantScopedReload() {
        var personal =
                new PayrollPersonalPolicy(
                        List.of(),
                        null,
                        LocalDate.of(2026, 9, 16),
                        new java.math.BigDecimal("80"),
                        null,
                        null,
                        null,
                        null,
                        new java.math.BigDecimal("92.5"));
        var salary =
                new PayrollSalaryStandard(
                        new PayrollSchemeBaseSalary(
                                "FIXED", new java.math.BigDecimal("3000"), List.of(), null),
                        new java.math.BigDecimal("1000"),
                        new PayrollProrationPolicy("NONE", "NONE", "NONE"),
                        personal);
        var definition =
                new PayrollComponentDefinition(
                        "试用期比例",
                        "SALARY",
                        "SALES",
                        List.of(),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(9999, 12, 31),
                        salary,
                        null,
                        null,
                        null,
                        null,
                        List.of("E1"));
        var saved =
                catalog.save(
                        "T1",
                        new PayrollComponentSaveCommand(
                                "probation-ratios", null, definition, "约定比例"),
                        "HR");
        var reloaded =
                catalog.versions("T1").stream()
                        .filter(v -> v.id().equals(saved.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(reloaded.definition().salary().personalPolicy().probationBasePercent())
                .isEqualByComparingTo("80");
        assertThat(reloaded.definition().salary().personalPolicy().postProbationBasePercent())
                .isEqualByComparingTo("92.5");
        assertThat(catalog.versions("T2")).noneMatch(v -> v.id().equals(saved.id()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTENDANCE", "PERFORMANCE", "COMMISSION", "ALLOWANCE", "SALARY"})
    void replacementRetiresOnlyItsScopeAndRetainsDurableAudit(String kind) {
        var d = rule.definition();
        var definition =
                new PayrollComponentDefinition(
                        "旧规则",
                        kind,
                        Set.of("ATTENDANCE", "COMMISSION").contains(kind) ? "ALL" : "SALES",
                        "SALARY".equals(kind) ? List.of() : List.of(1L, 2L),
                        d.effectiveFrom(),
                        d.effectiveTo(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "SALARY".equals(kind) ? List.of("E1") : List.of());
        rule =
                new PayrollComponentVersionView(
                        rule.id(),
                        rule.ruleId(),
                        1,
                        definition,
                        "创建",
                        "HR",
                        Instant.EPOCH,
                        rule.ruleCode());
        jdbc.update(
                "UPDATE hr_payroll_component_version SET kind=?, data_json=? WHERE id=?",
                kind,
                json.writeValueAsString(definition),
                rule.id());
        var stopped =
                store.act(
                                "T1",
                                rule,
                                new PayrollComponentActionCommand(
                                        "stop-replace", "DISABLE", 1, 0, "停用"),
                                "HR",
                                LocalDate.of(2026, 10, 26))
                        .rule();
        var laterDefinition =
                new PayrollComponentDefinition(
                        "旧规则后续版本",
                        kind,
                        definition.role(),
                        "SALARY".equals(kind) ? List.of() : List.of(1L, 3L),
                        LocalDate.of(2026, 10, 1),
                        definition.effectiveTo(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        definition.employeeCodes());
        var laterVersion =
                catalog.save(
                        "T1",
                        new PayrollComponentSaveCommand(
                                "later-version",
                                stopped.id(),
                                laterDefinition,
                                "旧规则后续版本",
                                false,
                                1),
                        "HR");
        var replacement =
                new PayrollComponentVersionView(
                        "V2",
                        "R2",
                        1,
                        new PayrollComponentDefinition(
                                "替代规则",
                                kind,
                                definition.role(),
                                "SALARY".equals(kind) ? List.of() : List.of(1L, 3L),
                                definition.effectiveFrom(),
                                definition.effectiveTo(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                definition.employeeCodes()),
                        "新建替代",
                        "HR",
                        Instant.now(),
                        "PCR002");
        payroll("affected-confirmed", "T1", "E1", "2026-09", 1, "CONFIRMED");
        payroll("affected-paid", "T1", "E1", "2026-09", 1, "PAID");
        payroll("other-department", "T1", "E2", "2026-09", 2, "CONFIRMED");
        payroll("other-tenant", "T2", "E1", "2026-09", 1, "CONFIRMED");
        payroll("before-replacement", "T1", "E1", "2026-08", 1, "CONFIRMED");
        if ("SALARY".equals(kind)) payroll("other-employee", "T1", "E2", "2026-09", 1, "CONFIRMED");
        if (Set.of("PERFORMANCE", "ALLOWANCE").contains(kind)) {
            payroll("other-role", "T1", "E3", "2026-09", 1, "CONFIRMED");
            jdbc.update(
                    "UPDATE hr_payroll SET calculation_json=? WHERE id='other-role'",
                    "{\"salarySource\":{\"payrollCategory\":\"CITY_MANAGER\"}}");
        }
        store.retire("T1", stopped, replacement, "HR");
        store.retire("T1", stopped, replacement, "HR");
        store.retire("T1", laterVersion, replacement, "HR");
        store.retire("T1", laterVersion, replacement, "HR");
        var saved =
                catalog.versions("T1").stream()
                        .filter(v -> v.id().equals(rule.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(saved.retired()).isTrue();
        assertThat(saved.enabledAt(LocalDate.of(2026, 9, 10))).isTrue();
        if ("SALARY".equals(kind)) {
            assertThat(saved.enabledForEmployeeAt("E1", LocalDate.of(2026, 9, 10))).isFalse();
            assertThat(saved.enabledForEmployeeAt("E1", LocalDate.of(2026, 8, 31))).isTrue();
        } else {
            assertThat(saved.enabledForDepartmentAt(1L, LocalDate.of(2026, 9, 10))).isFalse();
            assertThat(saved.enabledForDepartmentAt(2L, LocalDate.of(2026, 9, 10))).isTrue();
        }
        assertThat(saved.definition()).isEqualTo(rule.definition());
        assertThat(store.reviews("T1", "PAYROLL", "2026-09"))
                .extracting(PayrollRuleReviewView::subjectId)
                .containsExactlyInAnyOrder("affected-confirmed", "affected-paid");
        assertThat(store.reviews("T2", "PAYROLL", "2026-09")).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status_code FROM hr_payroll WHERE id='affected-paid'",
                                String.class))
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT calculation_json FROM hr_payroll WHERE"
                                        + " id='affected-confirmed'",
                                String.class))
                .isEqualTo("{}");
        assertThat(store.operations("T1", "R1"))
                .filteredOn(o -> "RETIRE".equals(o.action()))
                .hasSize(2);
        assertThat(saved.stateChanges())
                .filteredOn(PayrollComponentStateChange::retired)
                .extracting(PayrollComponentStateChange::effectiveMonth)
                .containsExactly("2026-09", "2026-10");
        assertThat(catalog.versions("T2")).isEmpty();
    }

    @Test
    void failedReplacementRollsBackNewVersionRetirementAndReviewsTogether() {
        var stopped =
                store.act(
                                "T1",
                                rule,
                                new PayrollComponentActionCommand(
                                        "stop-rollback", "DISABLE", 1, 0, "停用"),
                                "HR",
                                LocalDate.of(2026, 10, 26))
                        .rule();
        payroll("P1", "T1", "E1", "2026-09", 1, "CONFIRMED");
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(ds));
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            var replacement =
                                                    catalog.save(
                                                            "T1",
                                                            new PayrollComponentSaveCommand(
                                                                    "failed-replacement",
                                                                    null,
                                                                    rule.definition(),
                                                                    "替代后失败"),
                                                            "HR");
                                            store.retire("T1", stopped, replacement, "HR");
                                            throw new IllegalStateException("模拟事务失败");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(catalog.versions("T1")).hasSize(1);
        assertThat(catalog.versions("T1").getFirst().retired()).isFalse();
        assertThat(store.operations("T1", rule.ruleId()))
                .extracting(PayrollComponentOperationView::action)
                .containsExactly("DISABLE");
        assertThat(store.reviews("T1", "PAYROLL", "2026-09")).isEmpty();
    }

    @Test
    void editingStoppedRuleCreatesAnAuditedVersionThatStaysStoppedUntilEnabled() {
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var stopped =
                store.act(
                                "T1",
                                rule,
                                new PayrollComponentActionCommand(
                                        "stop-for-edit", "DISABLE", 1, 0, "停用修订"),
                                "HR",
                                today)
                        .rule();
        tools.jackson.databind.node.ObjectNode node = json.valueToTree(rule.definition());
        node.put("name", "修订后的销售考勤");
        node.put("effectiveFrom", YearMonth.from(today).atDay(1).toString());
        var definition = json.treeToValue(node, PayrollComponentDefinition.class);
        var revised =
                catalog.save(
                        "T1",
                        new PayrollComponentSaveCommand(
                                "stopped-edit", rule.id(), definition, "修正后待核对", false, 1),
                        "HR");
        assertThat(revised.version()).isEqualTo(2);
        assertThat(revised.id()).isNotEqualTo(rule.id());
        assertThat(revised.enabledAt(today)).isFalse();
        assertThat(catalog.versions("T1"))
                .filteredOn(version -> version.id().equals(rule.id()))
                .singleElement()
                .satisfies(
                        version -> assertThat(version.definition()).isEqualTo(rule.definition()));
        assertThat(store.operations("T1", rule.ruleId()))
                .anySatisfy(
                        operation -> {
                            assertThat(operation.action()).isEqualTo("MODIFY");
                            assertThat(operation.reason()).isEqualTo("修正后待核对");
                        });
        var enabled =
                store.act(
                                "T1",
                                revised,
                                new PayrollComponentActionCommand(
                                        "enable-edited",
                                        "ENABLE",
                                        revised.revision(),
                                        revised.stateRevision(),
                                        "已核对启用"),
                                "HR",
                                today)
                        .rule();
        assertThat(enabled.enabledAt(today)).isTrue();
        assertThat(enabled.definition()).isEqualTo(definition);
    }

    void execute(String file) throws Exception {
        String sql =
                Files.readString(Path.of("src/main/resources/db/migration", file))
                        .replaceAll("(?m)^\\s*--.*$", "")
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace(" JSON ", " CLOB ");
        for (String statement : sql.split(";")) {
            if (statement.isBlank()) continue;
            if (statement.contains("ALTER TABLE hr_payroll_component_version"))
                statement =
                        statement.replaceAll(
                                ",\\s*ADD COLUMN",
                                "; ALTER TABLE hr_payroll_component_version ADD COLUMN");
            jdbc.execute(statement);
        }
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    void payroll(
            String id,
            String tenant,
            String employee,
            String month,
            long department,
            String status) {
        jdbc.update(
                "INSERT INTO"
                    + " hr_payroll(id,tenant_id,employee_code,payroll_month,department_id,status_code,calculation_json,revision)"
                    + " VALUES(?,?,?,?,?,?,?,1)",
                id,
                tenant,
                employee,
                month,
                department,
                status,
                "{}");
    }

    @Test
    void lifecycleMarksConfirmedAndPaidRecordsWithinTenantScopeWithoutOverwritingTheirSnapshots() {
        payroll("P1", "T1", "E1", "2026-10", 1, "CONFIRMED");
        payroll("P2", "T1", "E2", "2026-10", 1, "PAID");
        payroll("old", "T1", "E1", "2026-09", 1, "CONFIRMED");
        payroll("otherTenant", "T2", "E1", "2026-10", 1, "CONFIRMED");
        payroll("otherDepartment", "T1", "E3", "2026-10", 2, "CONFIRMED");
        payroll("ready", "T1", "E4", "2026-10", 1, "READY");
        jdbc.update(
                "INSERT INTO"
                    + " hr_attendance_note(tenant_id,attendance_month,employee_code,attendance_status,confirmed_department_id,confirmed_snapshot_json)"
                    + " VALUES('T1','2026-10','E1','CONFIRMED',1,'{}'),('T2','2026-10','E1','CONFIRMED',1,'{}')");
        var command = new PayrollComponentActionCommand("stop", "DISABLE", 1, 0, "停用检查");
        var result = store.act("T1", rule, command, "HR", LocalDate.of(2026, 10, 20));
        assertThat(result.attendanceReviews()).isEqualTo(1);
        assertThat(result.payrollReviews()).isEqualTo(2);
        assertThat(store.reviews("T1", "PAYROLL", "2026-10")).hasSize(2);
        assertThat(store.reviews("T2", "PAYROLL", "2026-10")).isEmpty();
        assertThat(store.states("T1").get("R1").getFirst().enabled()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status_code FROM hr_payroll WHERE id='P1'", String.class))
                .isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT confirmed_snapshot_json FROM hr_attendance_note WHERE"
                                        + " tenant_id='T1'",
                                String.class))
                .isEqualTo("{}");
        assertThat(store.act("T1", rule, command, "HR", LocalDate.of(2026, 10, 20)))
                .isEqualTo(result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hr_payroll_component_operation",
                                Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                store.act(
                                        "T1",
                                        rule,
                                        new PayrollComponentActionCommand(
                                                "stale", "ENABLE", 1, 0, "过期状态"),
                                        "HR",
                                        LocalDate.of(2026, 10, 20)))
                .hasMessageContaining("状态已变更");
        store.resolveSubject("T1", "PAYROLL", "P1", "重新核算并确认", "HR");
        assertThat(store.reviews("T1", "PAYROLL", "2026-10"))
                .extracting(PayrollRuleReviewView::subjectId)
                .containsExactly("P2");
    }

    @Test
    void unstartedRuleCanBeUpdatedAndDeletedWithRevisionProtectionAndDurableRetry() {
        var d = rule.definition();
        var future =
                new PayrollComponentDefinition(
                        "待生效考勤",
                        d.kind(),
                        d.role(),
                        d.departmentIds(),
                        YearMonth.now(ZoneId.of("Asia/Shanghai")).plusMonths(1).atDay(1),
                        d.effectiveTo(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of());
        jdbc.update(
                "UPDATE hr_payroll_component_version SET data_json=? WHERE id='V1'",
                json.writeValueAsString(future));
        var command = new PayrollComponentSaveCommand("modify", "V1", future, "修改标准", true, 1);
        var updated = catalog.save("T1", command, "HR");
        assertThat(updated.id()).isEqualTo("V1");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(catalog.byRequest("T1", "modify")).contains(updated);
        assertThatThrownBy(
                        () ->
                                catalog.save(
                                        "T1",
                                        new PayrollComponentSaveCommand(
                                                "stale", "V1", future, "过期请求", true, 1),
                                        "HR"))
                .hasMessageContaining("请刷新");
        var deleted =
                store.act(
                        "T1",
                        updated,
                        new PayrollComponentActionCommand("delete", "DELETE", 2, 0, "取消配置"),
                        "HR",
                        LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(deleted.rule().deleted()).isTrue();
        assertThat(catalog.versions("T1").getFirst().deleted()).isTrue();
        assertThat(store.operations("T1", "R1"))
                .extracting(PayrollComponentOperationView::action)
                .containsExactly("DELETE", "MODIFY");
        assertThat(store.reviews("T1", "PAYROLL", "2026-10")).isEmpty();
    }
}
