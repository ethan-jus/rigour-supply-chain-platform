package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.AttendanceDepartmentOption;
import com.rigour.hr.api.v1.model.AttendanceExceptionPeriod;
import com.rigour.hr.api.v1.model.AttendanceRecordCommand;
import com.rigour.hr.api.v1.model.AttendanceRow;
import com.rigour.hr.api.v1.model.AttendanceRuleCommand;
import com.rigour.hr.api.v1.model.AttendanceRuleView;
import com.rigour.hr.application.port.out.HrAttendanceStore.EmployeeFilter;
import com.rigour.hr.application.service.HrAttendanceService;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

public class HrAttendancePersistenceTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusHrAttendanceStore store;
    HrDataScope scope;

    public static Timestamp utc(int precision) {
        return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
    }

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:attendance_" + UUID.randomUUID() + ";MODE=MySQL",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE ALIAS UTC_TIMESTAMP FOR"
                    + " 'com.rigour.hr.infrastructure.persistence.repository.HrAttendancePersistenceTest.utc'");
        jdbc.execute(
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V15__hr_attendance_rules_and_notes.sql"))
                        .replaceAll(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                ""));
        jdbc.update(
                "INSERT INTO"
                    + " hr_attendance_rule(tenant_id,attendance_month,expected_days,revision,updated_by)"
                    + " VALUES('T1','2026-08',24,3,'OLD')");
        jdbc.execute(
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V16__hr_attendance_department_rules.sql")));
        jdbc.update(
                "INSERT INTO"
                    + " hr_attendance_note(tenant_id,attendance_month,employee_code,reason,revision,updated_by)"
                    + " VALUES('OLD','2026-08','OLD','legacy note',3,'OLD')");
        // H2 MySQL mode does not support multiple ADD clauses in one ALTER TABLE.
        String adjustments =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V17__hr_attendance_manual_adjustments.sql"))
                        .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci", "")
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "");
        for (String statement : adjustments.split(";")) {
            if (!statement.contains("ALTER TABLE")) continue;
            String table =
                    statement.contains("ALTER TABLE hr_attendance_note")
                            ? "hr_attendance_note"
                            : "hr_attendance_audit";
            jdbc.execute(statement.replace(",\n    ADD", ";\nALTER TABLE " + table + " ADD"));
        }
        String confirmation =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V18__hr_attendance_confirmation.sql"))
                        .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci", "")
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "");
        for (String statement : confirmation.split(";")) {
            if (statement.isBlank()) continue;
            String table =
                    statement.contains("ALTER TABLE hr_attendance_note")
                            ? "hr_attendance_note"
                            : "hr_attendance_audit";
            jdbc.execute(statement.replace(",\n    ADD", ";\nALTER TABLE " + table + " ADD"));
        }
        jdbc.execute("ALTER TABLE hr_attendance_note ADD COLUMN exceptions_json CLOB NULL");
        jdbc.execute(
                "CREATE TABLE hr_employee(tenant_id VARCHAR(64),employee_code"
                        + " VARCHAR(50),employee_name VARCHAR(128),department_id BIGINT,entry_date"
                        + " DATETIME,leave_date DATETIME,deleted INT)");
        jdbc.execute(
                "CREATE TABLE hr_department(tenant_id VARCHAR(64),id BIGINT,department_name"
                        + " VARCHAR(128))");
        jdbc.execute(
                "CREATE TABLE hr_department_closure(tenant_id VARCHAR(64),ancestor_id"
                        + " BIGINT,descendant_id BIGINT,depth INT)");
        jdbc.update("INSERT INTO hr_department_closure VALUES('T1',1,1,0),('T2',1,1,0)");
        jdbc.update("INSERT INTO hr_department VALUES('T1',1,'销售部'),('T2',1,'其他租户')");
        jdbc.update(
                "INSERT INTO hr_employee"
                    + " VALUES('T1','E1','员工一',1,NULL,NULL,0),('T1','E2','员工二',1,NULL,NULL,0),('T2','E1','其他租户',1,NULL,NULL,0)");
        scope = mock(HrDataScope.class);
        when(scope.allDepartments(anyString())).thenReturn(true);
        when(scope.departmentPredicate(anyString())).thenReturn(new HrDataScope.Predicate("1=1", List.of()));
        var user = UUID.randomUUID();
        com.rigour.shared.context.TestAuthorizationContext.set(new com.rigour.shared.context.CallerIdentity(
                "TENANT", user, UUID.randomUUID(), user, null, UUID.randomUUID(), 1, 1, 1, Set.of(),
                Set.of(HrAttendanceService.RULE_READ, HrAttendanceService.RULE_WRITE)));
        when(scope.employeePredicate(anyString(), eq("e.")))
                .thenReturn(new HrDataScope.Predicate("e.employee_code=?", List.of("E1")));
        store = createStore(ds);
    }

    MybatisPlusHrAttendanceStore createStore(javax.sql.DataSource source) throws Exception {
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (var mapper :
                List.of(
                        HrAttendanceRuleMapper.class,
                        HrAttendanceNoteMapper.class,
                        HrAttendanceAuditMapper.class,
                        HrAttendanceMonthLockMapper.class,
                        HrAttendanceEmployeeMapper.class)) config.addMapper(mapper);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config);
        factory.setDataSource(source);
        var session = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var proxy =
                new ProxyFactory(
                        new MybatisPlusHrAttendanceStore(
                                session.getMapper(HrAttendanceRuleMapper.class),
                                session.getMapper(HrAttendanceNoteMapper.class),
                                session.getMapper(HrAttendanceAuditMapper.class),
                                session.getMapper(HrAttendanceMonthLockMapper.class),
                                session.getMapper(HrAttendanceEmployeeMapper.class),
                                scope,
                                new tools.jackson.databind.ObjectMapper()));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(source),
                        new AnnotationTransactionAttributeSource()));
        return (MybatisPlusHrAttendanceStore) proxy.getProxy();
    }

    @AfterEach
    void close() {
        com.rigour.shared.context.TestAuthorizationContext.clear();
        ds.destroy();
    }

    @Test
    void limitedDepartmentCannotWriteOtherDepartmentOrCompanyDefault() throws Exception {
        jdbc.execute("ALTER TABLE hr_department ADD status_code VARCHAR(16) DEFAULT 'ACTIVE'");
        jdbc.execute("ALTER TABLE hr_department ADD deleted INT DEFAULT 0");
        jdbc.execute("ALTER TABLE hr_department ADD parent_id BIGINT");
        jdbc.execute("ALTER TABLE hr_department ADD sort_order INT DEFAULT 0");
        jdbc.update("INSERT INTO hr_department(tenant_id,id,department_name,parent_id) VALUES('T1',2,'其他部门',NULL),('T1',3,'授权子部门',1)");
        jdbc.update("INSERT INTO hr_department_closure VALUES('T1',1,3,1)");
        var tenant = UUID.randomUUID();
        String tenantKey = tenant.toString();
        for (String table : List.of("hr_department", "hr_employee", "hr_department_closure"))
            jdbc.update("UPDATE " + table + " SET tenant_id=? WHERE tenant_id='T1'", tenantKey);
        var user = UUID.randomUUID();
        com.rigour.shared.context.TestAuthorizationContext.set(new com.rigour.shared.context.CallerIdentity(
                "TENANT", user, tenant, user, null, UUID.randomUUID(), 1, 1, 1, Set.of(),
                Set.of(HrAttendanceService.RULE_READ, HrAttendanceService.RULE_WRITE)));
        var client = mock(com.rigour.tenant.iam.client.SupplyAuthorizationClient.class);
        var all = new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit("ALL", List.of());
        var specified = new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit("SPECIFIED", List.of("1"));
        var descendants = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(client.authorization(any(), anyString())).thenAnswer(invocation ->
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView("ACTIVE", tenant, user, "E1",
                        1, 1, 1, 1, Set.of(HrAttendanceService.RULE_READ, HrAttendanceService.RULE_WRITE),
                        invocation.getArgument(1), true,
                        List.of(new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Clause(
                                UUID.randomUUID(), "EMPLOYEE", "DEPARTMENT", specified, all, all, descendants.get())), all, all));
        scope = new HrDataScope(client, jdbc);
        store = createStore(ds);
        store.saveRule(tenantKey, new AttendanceRuleCommand("2026-09", 1L, 22, 0), "HR");
        assertThatThrownBy(() -> store.saveRule(tenantKey, new AttendanceRuleCommand("2026-09", 2L, 21, 0), "HR"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> store.saveRule(tenantKey, new AttendanceRuleCommand("2026-09", null, 26, 0), "HR"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> store.saveRule(tenantKey, new AttendanceRuleCommand("2026-09", 3L, 22, 0), "HR"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_attendance_rule WHERE tenant_id=? AND attendance_month='2026-09'", Integer.class, tenantKey))
                .isEqualTo(1);
        descendants.set(true);
        store.saveRule(tenantKey, new AttendanceRuleCommand("2026-09", 3L, 22, 0), "HR");
        // Existing company and unrelated-department records predate this restricted assignment.
        jdbc.update("INSERT INTO hr_attendance_rule(tenant_id,attendance_month,department_id,expected_days,revision,updated_by) VALUES(?,'2026-09',0,26,1,'HR'),(?,'2026-09',2,25,1,'HR')", tenantKey, tenantKey);
        store.requireRuleScope(tenantKey, null, HrAttendanceService.RULE_READ);
        assertThat(store.rule(tenantKey, "2026-09", null).companyExpectedDays()).isEqualTo(26);
        assertThat(store.ruleWritable(tenantKey, null)).isFalse();
        assertThat(store.ruleWritable(tenantKey, 1L)).isTrue();
        assertThatThrownBy(() -> store.requireRuleScope(tenantKey, 2L, HrAttendanceService.RULE_READ))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(store.departments(tenantKey, HrAttendanceService.RULE_READ))
                .extracting(AttendanceDepartmentOption::id).containsExactly(1L, 3L);
        assertThat(store.ruleOverview(tenantKey, "2026-09").rules())
                .extracting(com.rigour.hr.application.port.out.HrAttendanceStore.RuleRecord::departmentId)
                .containsExactlyInAnyOrder(0L, 1L, 3L);
        jdbc.update("INSERT INTO hr_attendance_note(tenant_id,attendance_month,employee_code,attendance_status,confirmed_department_id,confirmed_rule_department_id,updated_by,confirmed_snapshot_json) VALUES(?,'2026-09','E1','CONFIRMED',1,1,'HR','{}'),(?,'2026-09','E2','CONFIRMED',2,2,'HR','{}')", tenantKey, tenantKey);
        assertThat(store.ruleOverview(tenantKey, "2026-09").lockedDepartmentIds()).containsExactly(1L);
        assertThatThrownBy(() -> store.saveRule(tenantKey, new AttendanceRuleCommand("2026-09", 1L, 21, 1), "HR"))
                .hasMessageContaining("已锁定");
        com.rigour.shared.context.TestAuthorizationContext.set(new com.rigour.shared.context.CallerIdentity(
                "TENANT", user, tenant, user, null, UUID.randomUUID(), 1, 1, 1, Set.of(), Set.of()));
        assertThatThrownBy(() -> store.saveRule(tenantKey, new AttendanceRuleCommand("2026-10", 1L, 22, 0), "HR"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void componentCalendarConfirmsAgainstItsEffectiveDaysInsteadOfLegacyCompanyCount() {
        var components = mock(com.rigour.hr.application.service.HrPayrollComponentService.class);
        var evidence = new com.rigour.hr.application.service.PayrollAttendanceEvidenceService(
                mock(com.rigour.hr.application.port.out.HrPayrollSchemeStore.class),
                mock(com.rigour.hr.application.port.out.HrPayrollSource.class));
        org.springframework.test.util.ReflectionTestUtils.setField(evidence, "components", components);
        var calendar = new com.rigour.hr.api.v1.model.PayrollWorkCalendar(
                List.of(1, 2, 3, 4, 5), List.of(), List.of(), List.of(), null, List.of(), null);
        var definition = new com.rigour.hr.api.v1.model.PayrollComponentDefinition(
                "部门工作日", "ATTENDANCE", "ALL", List.of(1L),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                null, null, null, calendar);
        var component = new com.rigour.hr.api.v1.model.PayrollComponentVersionView(
                "CALENDAR", "RULE", 1, definition, "登记日历", "HR", Instant.now());
        when(components.calendar("T1", 1L, LocalDate.of(2026, 9, 1)))
                .thenReturn(Optional.of(component));
        store.setPayrollAttendance(evidence);
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        int expected = evidence.expectedDays("T1", 1L, YearMonth.of(2026, 9)).orElseThrow();
        assertThat(expected).isEqualTo(22);
        var command = new AttendanceRecordCommand(new BigDecimal("22"), BigDecimal.ZERO, "NONE", "", 0);
        var snapshot = new AttendanceRow("E1", "员工一", "销售部", expected, "DEPARTMENT",
                new BigDecimal("22"), BigDecimal.ZERO, 0, new BigDecimal("22"), BigDecimal.ZERO,
                "NONE", "CONFIRMED", "AVAILABLE", List.of(), "", 1);
        store.confirmRecord("T1", "2026-09", "E1", command, snapshot, 1L, "HR");
        var confirmed = store.employee("T1", YearMonth.of(2026, 9), "E1").confirmation();
        assertThat(confirmed.expectedDays()).isEqualTo(22);
        assertThat(confirmed.ruleSource()).isEqualTo("DEPARTMENT");
        assertThat(store.rule("T1", "2026-09", null).expectedDays()).isEqualTo(26);
        assertThat(store.rule("T1", "2026-09", null).locked()).isFalse();
        assertThat(store.rule("T1", "2026-09", 1L).locked()).isTrue();
    }

    @Test
    void componentCalendarChangedBeforePersistenceRejectsStaleSnapshotWithoutWriting() {
        var evidence = mock(com.rigour.hr.application.service.PayrollAttendanceEvidenceService.class);
        when(evidence.expectedDays("T1", 1L, YearMonth.of(2026, 9))).thenReturn(Optional.of(21));
        store.setPayrollAttendance(evidence);
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        var command = new AttendanceRecordCommand(new BigDecimal("22"), BigDecimal.ZERO, "NONE", "", 0);
        var snapshot = new AttendanceRow("E1", "员工一", "销售部", 22, "DEPARTMENT",
                new BigDecimal("22"), BigDecimal.ZERO, 0, new BigDecimal("22"), BigDecimal.ZERO,
                "NONE", "CONFIRMED", "AVAILABLE", List.of(), "", 1);
        assertThatThrownBy(() -> store.confirmRecord("T1", "2026-09", "E1", command, snapshot, 1L, "HR"))
                .hasMessageContaining("规则已变更");
        assertThat(store.employee("T1", YearMonth.of(2026, 9), "E1").confirmation()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_attendance_audit WHERE action_code='CONFIRM'", Integer.class)).isZero();
    }

    @Test
    void stoppedOrUnavailableComponentCannotFallBackToLegacyRuleDuringConfirmation() {
        var evidence = mock(com.rigour.hr.application.service.PayrollAttendanceEvidenceService.class);
        when(evidence.expectedDays("T1", 1L, YearMonth.of(2026, 9))).thenReturn(Optional.of(22));
        when(evidence.stopped("T1", 1L, YearMonth.of(2026, 9))).thenReturn(true);
        store.setPayrollAttendance(evidence);
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        var command = new AttendanceRecordCommand(new BigDecimal("22"), BigDecimal.ZERO, "NONE", "", 0);
        var snapshot = new AttendanceRow("E1", "员工一", "销售部", 22, "DEPARTMENT",
                new BigDecimal("22"), BigDecimal.ZERO, 0, new BigDecimal("22"), BigDecimal.ZERO,
                "NONE", "CONFIRMED", "AVAILABLE", List.of(), "", 1);
        assertThatThrownBy(() -> store.confirmRecord("T1", "2026-09", "E1", command, snapshot, 1L, "HR"))
                .hasMessageContaining("规则已变更");
        when(evidence.stopped("T1", 1L, YearMonth.of(2026, 9))).thenReturn(false);
        when(evidence.expectedDays("T1", 1L, YearMonth.of(2026, 9)))
                .thenThrow(new com.rigour.shared.core.exception.BusinessException(
                        com.rigour.shared.core.api.ErrorCode.SERVICE_UNAVAILABLE, "年度日历不可用", List.of()));
        assertThatThrownBy(() -> store.confirmRecord("T1", "2026-09", "E1", command, snapshot, 1L, "HR"))
                .hasMessageContaining("年度日历不可用");
        assertThat(store.employee("T1", YearMonth.of(2026, 9), "E1").confirmation()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_attendance_audit WHERE action_code='CONFIRM'", Integer.class)).isZero();
    }

    @Test
    void employeeWithoutDepartmentStillConfirmsUsingCompanyRule() {
        jdbc.update("UPDATE hr_employee SET department_id=NULL WHERE tenant_id='T1' AND employee_code='E1'");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        var command = new AttendanceRecordCommand(new BigDecimal("26"), BigDecimal.ZERO, "NONE", "", 0);
        var snapshot = new AttendanceRow("E1", "员工一", null, 26, "COMPANY",
                new BigDecimal("26"), BigDecimal.ZERO, 0, new BigDecimal("26"), BigDecimal.ZERO,
                "NONE", "CONFIRMED", "AVAILABLE", List.of(), "", 1);
        store.confirmRecord("T1", "2026-09", "E1", command, snapshot, null, "HR");
        assertThat(store.employee("T1", YearMonth.of(2026, 9), "E1").confirmation().ruleSource())
                .isEqualTo("COMPANY");
        assertThat(store.rule("T1", "2026-09", null).locked()).isTrue();
    }

    @Test
    void rulesStayInTheirMonthAndTenantAndRejectLostUpdates() {
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "U");
        assertThat(store.rule("T1", "2026-10", null).expectedDays()).isNull();
        assertThat(store.rule("T2", "2026-09", null).expectedDays()).isNull();
        assertThatThrownBy(
                        () ->
                                store.saveRule(
                                        "T1",
                                        new AttendanceRuleCommand("2026-09", null, 22, 0),
                                        "U"))
                .hasMessageContaining("已变更");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 22, 1), "U");
        assertThat(store.rule("T1", "2026-09", null))
                .isEqualTo(new AttendanceRuleView("2026-09", null, 22, 2, 22, false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_attendance_audit", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void migrationPreservesCompanyRuleAndDepartmentRulesHaveIndependentRevisionsAndAudit() {
        assertThat(
                        jdbc.queryForMap(
                                "SELECT reason, revision, actual_days_override,"
                                        + " absent_days_override, leave_type_code FROM"
                                        + " hr_attendance_note WHERE tenant_id='OLD'"))
                .containsEntry("REASON", "legacy note")
                .containsEntry("REVISION", 3)
                .containsEntry("ACTUAL_DAYS_OVERRIDE", null)
                .containsEntry("ABSENT_DAYS_OVERRIDE", null)
                .containsEntry("LEAVE_TYPE_CODE", "NONE");
        assertThat(store.rule("T1", "2026-08", null))
                .isEqualTo(new AttendanceRuleView("2026-08", null, 24, 3, 24, false));
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "U");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 1L, 22, 0), "U");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 2L, 0, 0), "U");
        assertThat(store.rule("T1", "2026-09", 1L))
                .isEqualTo(new AttendanceRuleView("2026-09", 1L, 22, 1, 26, false));
        assertThat(store.rule("T1", "2026-09", 3L))
                .isEqualTo(new AttendanceRuleView("2026-09", 3L, null, 0, 26, false));
        assertThat(store.departmentRules("T1", "2026-09"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, 22, 2L, 0));
        assertThat(store.departmentRules("T2", "2026-09")).isEmpty();
        assertThat(store.departmentRules("T1", "2026-10")).isEmpty();
        assertThatThrownBy(
                        () ->
                                store.saveRule(
                                        "T1", new AttendanceRuleCommand("2026-09", 1L, 20, 0), "U"))
                .hasMessageContaining("已变更");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 1L, null, 1), "U");
        assertThat(store.rule("T1", "2026-09", 1L))
                .isEqualTo(new AttendanceRuleView("2026-09", 1L, null, 2, 26, false));
        assertThat(store.departmentRules("T1", "2026-09")).containsOnlyKeys(2L);
        assertThatThrownBy(
                        () ->
                                store.saveRule(
                                        "T1", new AttendanceRuleCommand("2026-09", 1L, 20, 1), "U"))
                .hasMessageContaining("已变更");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 25, 1), "U");
        assertThat(store.rule("T1", "2026-09", 1L).companyExpectedDays()).isEqualTo(25);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hr_attendance_audit WHERE department_id=1 AND"
                                        + " expected_days IS NULL AND revision=2",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void confirmedDepartmentLocksOnlyItsOwnRuleAndCannotBeReset() {
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 1L, 22, 0), "HR");
        assertThatThrownBy(
                        () ->
                                store.saveRule(
                                        "T1",
                                        new AttendanceRuleCommand("2026-09", 1L, 22, 1),
                                        "HR"))
                .hasMessageContaining("无需重复设置");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 1L, 23, 1), "HR");
        confirm(
                "T1",
                "2026-09",
                "E1",
                new AttendanceRecordCommand(BigDecimal.valueOf(22), BigDecimal.ONE, "NONE", "", 0),
                "HR");
        assertThat(store.rule("T1", "2026-09", 1L).locked()).isTrue();
        for (Integer days : Arrays.asList(24, null)) {
            assertThatThrownBy(
                            () ->
                                    store.saveRule(
                                            "T1",
                                            new AttendanceRuleCommand("2026-09", 1L, days, 2),
                                            "HR"))
                    .hasMessageContaining("已锁定");
        }
        // A parent's rule is not inherited; another department, month, and tenant stay independent.
        assertThat(store.rule("T1", "2026-09", null).locked()).isFalse();
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 25, 1), "HR");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 2L, 20, 0), "HR");
        store.saveRule("T1", new AttendanceRuleCommand("2026-10", 1L, 24, 0), "HR");
        store.saveRule("T2", new AttendanceRuleCommand("2026-09", 1L, 21, 0), "HR");
        assertThat(store.departmentRules("T1", "2026-09"))
                .containsEntry(1L, 23)
                .containsEntry(2L, 20);
        assertThat(store.employee("T1", YearMonth.of(2026, 9), "E1").confirmation().expectedDays())
                .isEqualTo(23);
        // Later staff transfers cannot unlock the rule used at confirmation.
        jdbc.update(
                "UPDATE hr_employee SET department_id=2 WHERE tenant_id='T1' AND"
                        + " employee_code='E1'");
        assertThat(store.rule("T1", "2026-09", 1L).locked()).isTrue();
        assertThat(store.rule("T1", "2026-09", 2L).locked()).isFalse();
    }

    @Test
    void companyFallbackConfirmationLocksCompanyAndPreventsCreatingADepartmentOverride() {
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        confirm(
                "T1",
                "2026-09",
                "E1",
                new AttendanceRecordCommand(BigDecimal.valueOf(26), BigDecimal.ZERO, "NONE", "", 0),
                "HR");
        assertThat(store.rule("T1", "2026-09", null).locked()).isTrue();
        assertThat(store.rule("T1", "2026-09", 1L).locked()).isTrue();
        assertThatThrownBy(
                        () ->
                                store.saveRule(
                                        "T1",
                                        new AttendanceRuleCommand("2026-09", null, 24, 1),
                                        "HR"))
                .hasMessageContaining("已锁定");
        assertThatThrownBy(
                        () ->
                                store.saveRule(
                                        "T1",
                                        new AttendanceRuleCommand("2026-09", 1L, 22, 0),
                                        "HR"))
                .hasMessageContaining("已锁定");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 2L, 20, 0), "HR");
        assertThat(store.rule("T1", "2026-09", 2L).locked()).isFalse();
    }

    @Test
    void staleRuleOrChangedDepartmentCannotBeConfirmed() {
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        var command =
                new AttendanceRecordCommand(BigDecimal.valueOf(26), BigDecimal.ZERO, "NONE", "", 0);
        var snapshot =
                new AttendanceRow(
                        "E1",
                        "员工",
                        "销售部",
                        26,
                        "COMPANY",
                        BigDecimal.valueOf(26),
                        BigDecimal.ZERO,
                        0,
                        command.actualDaysOverride(),
                        command.absentDaysOverride(),
                        "NONE",
                        "CONFIRMED",
                        "AVAILABLE",
                        List.of(),
                        "",
                        1);
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 24, 1), "HR");
        assertThatThrownBy(
                        () ->
                                store.confirmRecord(
                                        "T1", "2026-09", "E1", command, snapshot, 1L, "HR"))
                .hasMessageContaining("考勤规则已变更");
        jdbc.update(
                "UPDATE hr_employee SET department_id=2 WHERE tenant_id='T1' AND"
                        + " employee_code='E1'");
        assertThatThrownBy(
                        () ->
                                store.confirmRecord(
                                        "T1", "2026-09", "E1", command, snapshot, 1L, "HR"))
                .hasMessageContaining("员工部门已变更");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hr_attendance_note WHERE tenant_id='T1'",
                                Integer.class))
                .isZero();
    }

    @Test
    void concurrentRuleWriteWaitsForConfirmationCommitAndThenIsRejected() throws Exception {
        var caller = com.rigour.shared.context.AuthorizationContext.requireCurrent();
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        var parallelDs = new DriverManagerDataSource(ds.getUrl(), "sa", "");
        var parallelStore = createStore(parallelDs);
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(ds));
        var started = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending =
                    transaction.execute(
                            status -> {
                                confirm(
                                        "T1",
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                BigDecimal.valueOf(26),
                                                BigDecimal.ZERO,
                                                "NONE",
                                                "",
                                                0),
                                        "HR");
                                var future =
                                        executor.submit(
                                                () -> {
                                                    com.rigour.shared.context.TestAuthorizationContext.set(caller);
                                                    started.countDown();
                                                    return parallelStore.saveRule(
                                                            "T1",
                                                            new AttendanceRuleCommand(
                                                                    "2026-09", null, 24, 1),
                                                            "OTHER");
                                                });
                                assertThatCode(
                                                () ->
                                                        assertThat(
                                                                        started.await(
                                                                                2,
                                                                                java.util.concurrent
                                                                                        .TimeUnit
                                                                                        .SECONDS))
                                                                .isTrue())
                                        .doesNotThrowAnyException();
                                assertThatThrownBy(
                                                () ->
                                                        future.get(
                                                                200,
                                                                java.util.concurrent.TimeUnit
                                                                        .MILLISECONDS))
                                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                                return future;
                            });
            assertThatThrownBy(() -> pending.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasRootCauseMessage("当月关联员工已有考勤确认，对应规则已锁定，不能修改或重新设置");
        }
        assertThat(store.rule("T1", "2026-09", null).expectedDays()).isEqualTo(26);
    }

    @Test
    void departmentOptionsReadThroughMapperAndStayWithinTenant() {
        jdbc.execute("ALTER TABLE hr_department ADD parent_id BIGINT NULL");
        jdbc.execute("ALTER TABLE hr_department ADD sort_order INT DEFAULT 0");
        jdbc.execute("ALTER TABLE hr_department ADD deleted INT DEFAULT 0");
        var rows = store.departments("T1");
        assertThat(rows)
                .extracting(AttendanceDepartmentOption::departmentName)
                .containsExactly("销售部");
        jdbc.update("UPDATE hr_department SET deleted=1 WHERE tenant_id='T1'");
        assertThat(store.departments("T1")).isEmpty();
    }

    @Test
    void ruleOverviewIncludesOnlyConfiguredRulesAndSeparatelyTracksLockedFallbackDepartments() {
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 2L, 22, 0), "HR");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 3L, 24, 0), "HR");
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", 3L, null, 1), "HR");
        confirm(
                "T1",
                "2026-09",
                "E1",
                new AttendanceRecordCommand(BigDecimal.valueOf(26), BigDecimal.ZERO, "NONE", "", 0),
                "HR");
        var result = store.ruleOverview("T1", "2026-09");
        assertThat(result.rules())
                .extracting(r -> r.departmentId())
                .containsExactlyInAnyOrder(0L, 2L);
        assertThat(result.rules()).allSatisfy(r -> assertThat(r.updatedTime()).isNotNull());
        assertThat(result.lockedDepartmentIds()).containsExactlyInAnyOrder(0L, 1L);
        assertThat(store.ruleOverview("T2", "2026-09").rules()).isEmpty();
        assertThat(store.ruleOverview("T1", "2026-10").lockedDepartmentIds()).isEmpty();
    }

    @Test
    void notesAndEmployeePaginationRespectTenantAndEmployeeScope() {
        confirm(
                "T1",
                "2026-09",
                "E1",
                new AttendanceRecordCommand(null, null, "NONE", "漏打卡", 0),
                "U");
        var page =
                store.employees(
                        "T1", YearMonth.of(2026, 9), new EmployeeFilter("员工", "E1", 1L, ""), 0, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().record().reason()).isEqualTo("漏打卡");
        assertThat(page.items().getFirst().record().revision()).isEqualTo(1);
        assertThat(page.items().getFirst().departmentId()).isEqualTo(1L);
        assertThat(
                        store.employees(
                                        "T1",
                                        YearMonth.of(2026, 10),
                                        new EmployeeFilter("", "", null, ""),
                                        0,
                                        20)
                                .items()
                                .getFirst()
                                .record()
                                .reason())
                .isEmpty();
        assertThat(
                        store.employees(
                                        "T1",
                                        YearMonth.of(2026, 9),
                                        new EmployeeFilter("", "", null, ""),
                                        1,
                                        20)
                                .items())
                .isEmpty();
        assertThat(
                        store.employees(
                                        "T2",
                                        YearMonth.of(2026, 9),
                                        new EmployeeFilter("", "", null, ""),
                                        0,
                                        20)
                                .items()
                                .getFirst()
                                .record()
                                .reason())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                confirm(
                                        "T1",
                                        "2026-09",
                                        "E2",
                                        new AttendanceRecordCommand(null, null, "NONE", "越权", 0),
                                        "U"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(
                        () ->
                                confirm(
                                        "T1",
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(null, null, "NONE", "覆盖", 0),
                                        "U"))
                .hasMessageContaining("已变更");
        verify(scope, times(4)).employeePredicate(HrAttendanceService.READ, "e.");
    }

    @Test
    void filtersIntersectAndDepartmentIncludesDescendantsWithoutCrossingTenantOrScope() {
        when(scope.employeePredicate(anyString(), eq("e.")))
                .thenReturn(new HrDataScope.Predicate("e.employee_code<>?", List.of("HIDDEN")));
        jdbc.update("INSERT INTO hr_department VALUES('T1',2,'分部'),('T1',3,'其他部门')");
        jdbc.update(
                "INSERT INTO hr_department_closure"
                        + " VALUES('T1',1,2,1),('T1',2,2,0),('T1',3,3,0),('T2',1,3,1)");
        jdbc.update(
                "INSERT INTO hr_employee"
                    + " VALUES('T1','C3','员工三',2,NULL,NULL,0),('T1','C4','员工四',3,NULL,NULL,0),('T1','HIDDEN','员工五',2,NULL,NULL,0)");
        var month = YearMonth.of(2026, 9);
        var parent = store.employees("T1", month, new EmployeeFilter("员工", "", 1L, ""), 0, 2);
        assertThat(parent.total()).isEqualTo(3);
        assertThat(parent.items()).hasSize(2);
        assertThat(store.employees("T1", month, new EmployeeFilter("", "", 2L, ""), 0, 20).items())
                .extracting(e -> e.code())
                .containsExactly("C3");
        assertThat(
                        store.employees("T1", month, new EmployeeFilter("三", "C", 1L, ""), 0, 20)
                                .total())
                .isEqualTo(1);
        assertThat(
                        store.employees("T1", month, new EmployeeFilter("三", "E1", 1L, ""), 0, 20)
                                .total())
                .isZero();
        assertThat(
                        store.employees("T1", month, new EmployeeFilter("销售", "", null, ""), 0, 20)
                                .total())
                .isZero();
        assertThat(
                        store.employees("T1", month, new EmployeeFilter("", "员工", null, ""), 0, 20)
                                .total())
                .isZero();
        assertThat(
                        store.employees("T1", month, new EmployeeFilter("", "%", null, ""), 0, 20)
                                .total())
                .isZero();
        assertThat(
                        store.employees("T1", month, new EmployeeFilter("", "", 999L, ""), 0, 20)
                                .total())
                .isZero();
    }

    @Test
    void auditFailureRollsBackTheBusinessWrite() {
        jdbc.execute(
                "ALTER TABLE hr_attendance_audit ADD CONSTRAINT reject_audit"
                        + " CHECK(actor_id<>'FAIL')");
        assertThatThrownBy(
                        () ->
                                store.saveRule(
                                        "T1",
                                        new AttendanceRuleCommand("2026-09", null, 26, 0),
                                        "FAIL"))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.rule("T1", "2026-09", null).expectedDays()).isNull();
        assertThatThrownBy(
                        () ->
                                confirm(
                                        "T1",
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                new BigDecimal("20.5"),
                                                new BigDecimal("0.5"),
                                                "SICK",
                                                "test",
                                                0),
                                        "FAIL"))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hr_attendance_note WHERE tenant_id='T1'",
                                Integer.class))
                .isZero();
    }

    @Test
    void confirmedRecordsPersistHalfDaysAndAuditAndCannotBeChanged() {
        var exceptions =
                List.of(
                        new AttendanceExceptionPeriod(
                                java.time.LocalDateTime.parse("2026-09-10T18:00:00"),
                                java.time.LocalDateTime.parse("2026-09-11T12:00:00"),
                                "COMPENSATORY_LEAVE",
                                "活动后跨日调休"));
        var command =
                new AttendanceRecordCommand(
                        new BigDecimal("20.5"), new BigDecimal("2.5"), "SICK", "核实", 0, exceptions);
        confirm("T1", "2026-09", "E1", command, "HR1");
        var record =
                store.employees(
                                "T1",
                                YearMonth.of(2026, 9),
                                new EmployeeFilter("", "", null, ""),
                                0,
                                20)
                        .items()
                        .getFirst()
                        .record();
        assertThat(record.actualDaysOverride()).isEqualByComparingTo("20.5");
        assertThat(record.absentDaysOverride()).isEqualByComparingTo("2.5");
        assertThat(record.leaveTypeCode()).isEqualTo("SICK");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM hr_attendance_audit", String.class))
                .isEqualTo("HR1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actual_days_override FROM hr_attendance_audit",
                                BigDecimal.class))
                .isEqualByComparingTo("20.5");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT leave_type_code FROM hr_attendance_audit", String.class))
                .isEqualTo("SICK");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT updated_time FROM hr_attendance_note WHERE tenant_id='T1'",
                                Timestamp.class))
                .isNotNull();
        assertThatThrownBy(
                        () ->
                                confirm(
                                        "T1",
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(null, null, "NONE", "", 1),
                                        "HR2"))
                .hasMessageContaining("已变更");
        var frozen = store.employee("T1", YearMonth.of(2026, 9), "E1").confirmation();
        assertThat(frozen.attendanceStatus()).isEqualTo("CONFIRMED");
        assertThat(frozen.actualDays()).isEqualByComparingTo("20.5");
        assertThat(frozen.exceptions()).isEqualTo(exceptions);
        assertThat(frozen.dates())
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT confirmed_snapshot_json FROM hr_attendance_audit",
                                String.class))
                .contains("COMPENSATORY_LEAVE", "活动后跨日调休");
        assertThatThrownBy(() -> confirm("T1", "2026-09", "E1", command, "HR1"))
                .hasMessageContaining("已变更");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_attendance_audit", Integer.class))
                .isEqualTo(1);
        assertThat(
                        store.employees(
                                        "T1",
                                        YearMonth.of(2026, 10),
                                        new EmployeeFilter("", "", null, ""),
                                        0,
                                        20)
                                .items()
                                .getFirst()
                                .record()
                                .actualDaysOverride())
                .isNull();
        assertThat(
                        store.employees(
                                        "T2",
                                        YearMonth.of(2026, 9),
                                        new EmployeeFilter("", "", null, ""),
                                        0,
                                        20)
                                .items()
                                .getFirst()
                                .record()
                                .actualDaysOverride())
                .isNull();
    }

    @Test
    void reopeningKeepsTheOriginalConfirmationAuditAndExceptionsAndRejectsStaleRevision() {
        store.saveRule("T1", new AttendanceRuleCommand("2026-09", null, 26, 0), "HR");
        var periods =
                List.of(
                        new AttendanceExceptionPeriod(
                                LocalDateTime.of(2026, 9, 2, 9, 0),
                                LocalDateTime.of(2026, 9, 2, 12, 0),
                                "PAID_LEAVE",
                                "已报备"));
        confirm(
                "T1",
                "2026-09",
                "E1",
                new AttendanceRecordCommand(
                        BigDecimal.valueOf(25.5),
                        BigDecimal.valueOf(0.5),
                        "PAID",
                        "原确认",
                        0,
                        periods),
                "HR");
        store.reopenRecord(
                "T1",
                "2026-09",
                "E1",
                new com.rigour.hr.api.v1.model.AttendanceReopenCommand(1, "规则调整"),
                "HR2");
        var pending = store.employee("T1", YearMonth.of(2026, 9), "E1");
        assertThat(pending.confirmation()).isNull();
        assertThat(pending.record().revision()).isEqualTo(2);
        assertThat(pending.record().exceptions()).isEqualTo(periods);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hr_attendance_audit WHERE"
                                        + " action_code='REOPEN' AND confirmed_snapshot_json IS NOT"
                                        + " NULL",
                                Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                store.reopenRecord(
                                        "T1",
                                        "2026-09",
                                        "E1",
                                        new com.rigour.hr.api.v1.model.AttendanceReopenCommand(
                                                1, "重复"),
                                        "HR2"))
                .hasMessageContaining("已变更");
        assertThat(store.employee("T2", YearMonth.of(2026, 9), "E1").confirmation()).isNull();
    }

    void confirm(
            String tenant,
            String month,
            String employee,
            AttendanceRecordCommand command,
            String actor) {
        var rule = store.rule(tenant, month, 1L);
        Integer expected =
                rule.expectedDays() == null ? rule.companyExpectedDays() : rule.expectedDays();
        String source =
                expected == null ? "UNSET" : rule.expectedDays() == null ? "COMPANY" : "DEPARTMENT";
        var snapshot =
                new AttendanceRow(
                        employee,
                        "员工",
                        "销售部",
                        expected,
                        source,
                        command.actualDaysOverride(),
                        command.absentDaysOverride(),
                        2,
                        command.actualDaysOverride(),
                        command.absentDaysOverride(),
                        command.leaveTypeCode(),
                        "CONFIRMED",
                        "AVAILABLE",
                        List.of(LocalDate.parse(month + "-01"), LocalDate.parse(month + "-02")),
                        command.reason(),
                        command.revision() + 1,
                        command.exceptions());
        store.confirmRecord(tenant, month, employee, command, snapshot, 1L, actor);
    }

    @Test
    void statusFiltersBeforeCountingAndPagingWithMonthTenantAndDataScopeIsolation() {
        when(scope.employeePredicate(anyString(), eq("e.")))
                .thenReturn(new HrDataScope.Predicate("e.employee_code<>?", List.of("HIDDEN")));
        var month = YearMonth.of(2026, 9);
        assertThat(
                        store.employees(
                                        "T1",
                                        month,
                                        new EmployeeFilter("", "", null, "PENDING"),
                                        0,
                                        1)
                                .total())
                .isEqualTo(2);
        assertThat(
                        store.employees(
                                        "T1",
                                        month,
                                        new EmployeeFilter("", "", null, "CONFIRMED"),
                                        0,
                                        1)
                                .total())
                .isZero();
        confirm(
                "T1",
                "2026-09",
                "E1",
                new AttendanceRecordCommand(new BigDecimal("20.5"), BigDecimal.ZERO, "NONE", "", 0),
                "HR");
        var pending =
                store.employees("T1", month, new EmployeeFilter("", "", null, "PENDING"), 0, 1);
        assertThat(pending.total()).isEqualTo(1);
        assertThat(pending.items()).extracting(e -> e.code()).containsExactly("E2");
        var confirmed =
                store.employees(
                        "T1", month, new EmployeeFilter("员工一", "E1", 1L, "CONFIRMED"), 0, 1);
        assertThat(confirmed.total()).isEqualTo(1);
        assertThat(confirmed.items().getFirst().confirmation().dates()).hasSize(2);
        assertThat(
                        store.employees(
                                        "T1",
                                        month,
                                        new EmployeeFilter("", "", null, "CONFIRMED"),
                                        1,
                                        1)
                                .items())
                .isEmpty();
        assertThat(
                        store.employees(
                                        "T2",
                                        month,
                                        new EmployeeFilter("", "", null, "CONFIRMED"),
                                        0,
                                        1)
                                .total())
                .isZero();
        assertThat(
                        store.employees(
                                        "T1",
                                        month.plusMonths(1),
                                        new EmployeeFilter("", "", null, "CONFIRMED"),
                                        0,
                                        1)
                                .total())
                .isZero();
        when(scope.employeePredicate(anyString(), eq("e.")))
                .thenReturn(new HrDataScope.Predicate("e.employee_code=?", List.of("E2")));
        assertThat(
                        store.employees(
                                        "T1",
                                        month,
                                        new EmployeeFilter("", "", null, "CONFIRMED"),
                                        0,
                                        20)
                                .total())
                .isZero();
        assertThatThrownBy(() -> store.employee("T1", month, "E1"))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> store.employee("T1", month, "E"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void legacyNotesRemainPendingAndConfirmOnceUsingTheirExistingRevision() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attendance_status FROM hr_attendance_note WHERE"
                                        + " tenant_id='OLD'",
                                String.class))
                .isEqualTo("PENDING");
        jdbc.update(
                "INSERT INTO"
                    + " hr_attendance_note(tenant_id,attendance_month,employee_code,reason,revision,updated_by)"
                    + " VALUES('T1','2026-09','E1','legacy',3,'HR')");
        assertThat(store.employee("T1", YearMonth.of(2026, 9), "E1").confirmation()).isNull();
        confirm(
                "T1",
                "2026-09",
                "E1",
                new AttendanceRecordCommand(null, null, "NONE", "legacy", 3),
                "HR");
        assertThat(store.employee("T1", YearMonth.of(2026, 9), "E1").confirmation().revision())
                .isEqualTo(4);
        assertThatThrownBy(
                        () ->
                                confirm(
                                        "T1",
                                        "2026-09",
                                        "E1",
                                        new AttendanceRecordCommand(
                                                null, null, "NONE", "changed", 4),
                                        "HR"))
                .hasMessageContaining("已变更");
    }
}
