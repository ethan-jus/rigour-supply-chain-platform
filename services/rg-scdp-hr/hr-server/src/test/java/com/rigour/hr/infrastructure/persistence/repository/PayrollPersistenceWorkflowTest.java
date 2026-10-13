package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** 隔离 H2 验证真实 MyBatis 映射及事务；不代替 MySQL 迁移验收。 */
class PayrollPersistenceWorkflowTest {
    static final String DATABASE = "payroll_" + UUID.randomUUID();
    java.sql.Connection schemaConnection;
    JdbcTemplate jdbc;
    MybatisPlusHrPayrollRepository payroll;
    MybatisPlusHrExpenseRepository expenses;
    HrPayrollPersistenceSupport support;
    HrPayrollReferenceMapper references;
    MybatisPlusHrEmployeePayrollProfileRepository profiles;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + DATABASE
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        String ddl =
                new ClassPathResource("db/migration/V10__hr_payroll_and_expenses.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        // H2 JSON serializes strings differently, so only snapshot storage uses a textual test
        // column.
        ddl =
                ddl.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci", "")
                        .replace(" JSON ", " LONGTEXT ");
        schemaConnection = ds.getConnection();
        schemaConnection.createStatement().execute(ddl);
        schemaConnection
                .createStatement()
                .execute(
                        new ClassPathResource("db/migration/V13__hr_payroll_workflow.sql")
                                .getContentAsString(StandardCharsets.UTF_8)
                                .replace(
                                        "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                                                + " COLLATE=utf8mb4_general_ci",
                                        ""));
        jdbc.execute(
                "CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR"
                    + " 'com.rigour.hr.infrastructure.persistence.repository.PayrollPersistenceWorkflowTest.utcNow'");
        schemaConnection
                .createStatement()
                .execute(
                        new ClassPathResource(
                                        "db/migration/V14__hr_payroll_monthly_department_override.sql")
                                .getContentAsString(StandardCharsets.UTF_8));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (var type :
                List.of(
                        HrPayrollMapper.class,
                        HrEmployeeMapper.class,
                        HrEmployeeSalaryMapper.class,
                        HrPayrollPaymentMapper.class,
                        HrPayrollCorrectionMapper.class,
                        HrPayrollCommissionReleaseMapper.class,
                        HrPayrollRuleMapper.class,
                        HrPayrollReferenceMapper.class,
                        HrExpenseMapper.class,
                        HrPayrollAuditMapper.class)) config.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var json = JsonMapper.builder().build();
        var scope = mock(HrDataScope.class);
        when(scope.apply(any(), anyString())).thenAnswer(i -> i.getArgument(0));
        when(scope.employeePredicate(anyString(), anyString()))
                .thenReturn(new HrDataScope.Predicate("1=1", List.of()));
        references = spy(session.getMapper(HrPayrollReferenceMapper.class));
        support =
                new HrPayrollPersistenceSupport(
                        session.getMapper(HrPayrollMapper.class),
                        session.getMapper(HrPayrollAuditMapper.class),
                        json);
        payroll =
                proxy(
                        new MybatisPlusHrPayrollRepository(
                                session.getMapper(HrPayrollMapper.class),
                                session.getMapper(HrPayrollRuleMapper.class),
                                references,
                                support,
                                json,
                                scope,
                                session.getMapper(HrPayrollPaymentMapper.class),
                                session.getMapper(HrPayrollCorrectionMapper.class),
                                session.getMapper(HrPayrollCommissionReleaseMapper.class)),
                        ds);
        var actorNames = mock(com.rigour.hr.application.port.out.HrAuditActorNameResolver.class);
        when(actorNames.resolve(anyString(), anyString())).thenReturn("测试HR");
        profiles =
                proxy(
                        new MybatisPlusHrEmployeePayrollProfileRepository(
                                session.getMapper(HrEmployeeMapper.class),
                                session.getMapper(HrEmployeeSalaryMapper.class),
                                scope,
                                support,
                                actorNames),
                        ds);
        expenses =
                proxy(
                        new MybatisPlusHrExpenseRepository(
                                session.getMapper(HrExpenseMapper.class), support, json, scope),
                        ds);
    }

    void employeeProfileFixture() {
        jdbc.execute(
                "CREATE TABLE hr_employee_profile(tenant_id VARCHAR(50), employee_id BIGINT,"
                        + " probation_salary VARCHAR(64), regular_salary VARCHAR(64), private_field"
                        + " VARCHAR(100), PRIMARY KEY(tenant_id,employee_id))");
        jdbc.update(
                "INSERT INTO hr_employee_profile"
                        + " VALUES('T1',1,'3000','4000','preserved'),('T2',1,'9000','10000','other"
                        + " tenant')");
        var columns = new java.util.ArrayList<String>();
        for (var f :
                com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity.class
                        .getFields()) {
            String name = f.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
            String type =
                    f.getType() == Long.class
                            ? "BIGINT"
                            : f.getType() == Integer.class
                                    ? "INT"
                                    : f.getType() == Boolean.class
                                            ? "BOOLEAN"
                                            : f.getType() == LocalDate.class
                                                    ? "DATE"
                                                    : f.getType() == LocalDateTime.class
                                                            ? "TIMESTAMP"
                                                            : "VARCHAR(4000)";
            columns.add(name + " " + type);
        }
        jdbc.execute("CREATE TABLE hr_employee(" + String.join(",", columns) + ")");
        jdbc.update(
                "INSERT INTO"
                    + " hr_employee(id,tenant_id,employee_code,employee_name,department_id,revision,deleted,regularized,regularization_date,entry_date,remark,updated_by_name)"
                    + " VALUES"
                    + " (1,'T1','E1','员工1',10,3,0,TRUE,'2025-01-01','2024-01-01','保留备注','原操作人'),(2,'T1','E2','员工2',10,3,0,FALSE,NULL,'2024-01-01','保留备注','原操作人'),(3,'T2','E1','其他租户',10,3,0,FALSE,NULL,'2024-01-01','保留备注','原操作人')");
    }

    @Test
    void profileBatchRollsBackEarlierRowsAndAuditsWhenAnyRevisionIsStale() {
        employeeProfileFixture();
        var first = new PayrollProfilePatchCommand("E1", 3, "SALES", null, null, null, null, null);
        var stale =
                new PayrollProfilePatchCommand(
                        "E2", 2, "HEADQUARTERS", null, null, null, null, null);
        assertThatThrownBy(
                        () ->
                                profiles.update(
                                        "T1",
                                        new PayrollBatchProfileCommand(
                                                List.of(first, stale), "补齐类别"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM hr_employee WHERE id=1", Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payroll_category FROM hr_employee WHERE id=1",
                                String.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_payroll_audit", Integer.class))
                .isZero();
        profiles.update("T1", new PayrollBatchProfileCommand(List.of(first), "核实类别"), "ACTOR");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payroll_category FROM hr_employee WHERE id=1",
                                String.class))
                .isEqualTo("SALES");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT regularized FROM hr_employee WHERE id=1", Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT updated_by_name FROM hr_employee WHERE id=1", String.class))
                .isEqualTo("测试HR");
        assertThat(jdbc.queryForObject("SELECT remark FROM hr_employee WHERE id=1", String.class))
                .isEqualTo("保留备注");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM hr_employee WHERE id=3", Integer.class))
                .isEqualTo(3);
        assertThat(support.history("T1", List.of("EMPLOYEE:E1"))).hasSize(2);
        var after =
                support.history("T1", List.of("EMPLOYEE:E1")).stream()
                        .filter(a -> a.action().equals("PROFILE_BATCH"))
                        .findFirst()
                        .orElseThrow();
        assertThat(after.snapshotJson()).contains("\"revision\":4", "\"regularized\":true");
        assertThat(support.history("T2", List.of("EMPLOYEE:E1"))).isEmpty();
    }

    @Test
    void salaryPatchPreservesOmittedFieldsAndOtherTenantAndRollsBackOnConflict() {
        employeeProfileFixture();
        var first =
                new PayrollProfilePatchCommand(
                        "E1", 3, "SALES", null, null, LocalDate.of(2026, 9, 1), "3500.50", null);
        var stale =
                new PayrollProfilePatchCommand(
                        "E2", 2, "SALES", null, null, LocalDate.of(2026, 9, 1), "3200", "4200");
        assertThatThrownBy(
                        () ->
                                profiles.update(
                                        "T1",
                                        new PayrollBatchProfileCommand(
                                                List.of(first, stale), "测试工资"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT probation_salary FROM hr_employee_profile WHERE"
                                        + " tenant_id='T1' AND employee_id=1",
                                String.class))
                .isEqualTo("3000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_payroll_audit", Integer.class))
                .isZero();
        profiles.update("T1", new PayrollBatchProfileCommand(List.of(first), "测试工资"), "ACTOR");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT probation_salary FROM hr_employee_profile WHERE"
                                        + " tenant_id='T1' AND employee_id=1",
                                String.class))
                .isEqualTo("3500.50");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT regular_salary FROM hr_employee_profile WHERE"
                                        + " tenant_id='T1' AND employee_id=1",
                                String.class))
                .isEqualTo("4000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT private_field FROM hr_employee_profile WHERE tenant_id='T1'"
                                        + " AND employee_id=1",
                                String.class))
                .isEqualTo("preserved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT probation_salary FROM hr_employee_profile WHERE"
                                        + " tenant_id='T2' AND employee_id=1",
                                String.class))
                .isEqualTo("9000");
        var history = support.history("T1", List.of("EMPLOYEE:E1"));
        assertThat(history)
                .anySatisfy(
                        a -> {
                            assertThat(a.action()).isEqualTo("PROFILE_BEFORE");
                            assertThat(a.snapshotJson())
                                    .contains("3000")
                                    .doesNotContain("private_field");
                        })
                .anySatisfy(
                        a -> {
                            assertThat(a.action()).isEqualTo("PROFILE_BATCH");
                            assertThat(a.snapshotJson()).contains("3500.50", "4000");
                        });
        var added =
                new PayrollProfilePatchCommand(
                        "E2", 3, "SALES", null, null, LocalDate.of(2026, 9, 1), "3200", "4200");
        profiles.update("T1", new PayrollBatchProfileCommand(List.of(added), "测试工资"), "ACTOR");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT regular_salary FROM hr_employee_profile WHERE"
                                        + " tenant_id='T1' AND employee_id=2",
                                String.class))
                .isEqualTo("4200");
    }

    @Test
    void salaryChangesRequireSalesCategoryAndEffectiveDate() {
        employeeProfileFixture();
        var headquarters =
                new PayrollProfilePatchCommand(
                        "E1",
                        3,
                        "HEADQUARTERS",
                        null,
                        null,
                        LocalDate.of(2026, 9, 1),
                        "4000",
                        null);
        assertThatThrownBy(
                        () ->
                                profiles.update(
                                        "T1",
                                        new PayrollBatchProfileCommand(List.of(headquarters), "测试"),
                                        "ACTOR"))
                .hasMessageContaining("仅适用于销售");
        var missingDate =
                new PayrollProfilePatchCommand("E1", 3, "SALES", null, null, null, "4000", null);
        assertThatThrownBy(
                        () ->
                                profiles.update(
                                        "T1",
                                        new PayrollBatchProfileCommand(List.of(missingDate), "测试"),
                                        "ACTOR"))
                .hasMessageContaining("生效日期");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM hr_employee WHERE id=1", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_payroll_audit", Integer.class))
                .isZero();
    }

    PayrollDetailView confirmed() {
        var saved =
                payroll.saveCalculation(
                        "T1", "E1", "2026-09", employee(), calculation("A"), "ACTOR");
        return payroll.transition(
                "T1",
                saved.id(),
                new PayrollActionCommand(saved.revision(), "确认"),
                "CONFIRMED",
                "ACTOR");
    }

    @Test
    void partialPaymentsAreAuditedIdempotentAndCannotExceedNetPay() {
        var confirmed = confirmed();
        var first =
                new PayrollPaymentCommand(
                        UUID.randomUUID().toString(),
                        confirmed.revision(),
                        LocalDate.of(2026, 10, 1),
                        new BigDecimal("1000"),
                        "BANK-1",
                        "首笔");
        var partial = payroll.pay("T1", confirmed.id(), first, "ACTOR");
        assertThat(partial.statusCode()).isEqualTo("CONFIRMED");
        assertThat(partial.payments()).hasSize(1);
        assertThat(payroll.pay("T1", confirmed.id(), first, "ACTOR").revision())
                .isEqualTo(partial.revision());
        assertThatThrownBy(() -> payroll.pay("T2", confirmed.id(), first, "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                payroll.pay(
                                        "T1",
                                        confirmed.id(),
                                        new PayrollPaymentCommand(
                                                UUID.randomUUID().toString(),
                                                partial.revision(),
                                                LocalDate.of(2026, 10, 1),
                                                new BigDecimal("2041"),
                                                "BANK-2",
                                                "超额"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                payroll.pay(
                                        "T1",
                                        confirmed.id(),
                                        new PayrollPaymentCommand(
                                                UUID.randomUUID().toString(),
                                                partial.revision(),
                                                LocalDate.of(2026, 10, 1),
                                                new BigDecimal("1"),
                                                "BANK-1",
                                                "重复凭证"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                payroll.reopen(
                                        "T1",
                                        confirmed.id(),
                                        new PayrollActionCommand(partial.revision(), "已付款不能撤回"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        var paid =
                payroll.pay(
                        "T1",
                        confirmed.id(),
                        new PayrollPaymentCommand(
                                UUID.randomUUID().toString(),
                                partial.revision(),
                                LocalDate.of(2026, 10, 2),
                                new BigDecimal("2040"),
                                "BANK-2",
                                "尾款"),
                        "ACTOR");
        assertThat(paid.statusCode()).isEqualTo("PAID");
        assertThat(paid.payments()).hasSize(2);
        assertThat(payroll.history("T1", paid.id()))
                .filteredOn(x -> x.action().equals("PAYMENT_REGISTER"))
                .hasSize(2);
        assertThat(payroll.released("T1")).containsExactly("LINE1|SALES");
    }

    @Test
    void reopenPreservesSnapshotAndOnlyReleasesThatPayrollCommission() {
        var confirmed = confirmed();
        jdbc.update(
                "INSERT INTO"
                    + " hr_payroll_commission_release(tenant_id,source_line_id,recipient_role_code,payroll_id,amount)"
                    + " VALUES('T1','LINE2','SALES','OTHER',5)");
        var reopened =
                payroll.reopen(
                        "T1",
                        confirmed.id(),
                        new PayrollActionCommand(confirmed.revision(), "底薪填错"),
                        "ACTOR");
        assertThat(reopened.statusCode()).isEqualTo("REVIEW");
        assertThat(reopened.calculation().sourceFingerprint()).isEqualTo("A");
        assertThat(payroll.released("T1")).containsExactly("LINE2|SALES");
        assertThat(payroll.history("T1", reopened.id()))
                .extracting(PayrollAuditView::action)
                .contains("BEFORE_REOPEN", "REOPEN");
        assertThatThrownBy(
                        () ->
                                payroll.transition(
                                        "T1",
                                        reopened.id(),
                                        new PayrollActionCommand(reopened.revision(), "未重算"),
                                        "CONFIRMED",
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void correctionInvalidatesTargetAndVoidKeepsPaidSourceUnchanged() {
        doReturn(List.of(employee())).when(references).employees(any());
        var confirmed = confirmed();
        var paid =
                payroll.pay(
                        "T1",
                        confirmed.id(),
                        new PayrollPaymentCommand(
                                "PAY-1",
                                confirmed.revision(),
                                LocalDate.of(2026, 10, 1),
                                new BigDecimal("3040"),
                                "BANK-1",
                                "发放"),
                        "ACTOR");
        var target =
                payroll.saveCalculation(
                        "T1", "E1", "2026-10", employee(), calculation("TARGET"), "ACTOR");
        var command =
                new PayrollCorrectionCommand(
                        "CORR-1", paid.revision(), "2026-10", new BigDecimal("-200"), "冲回重复补贴");
        var correction = payroll.correct("T1", paid.id(), command, "ACTOR");
        assertThat(payroll.correct("T1", paid.id(), command, "ACTOR").id())
                .isEqualTo(correction.id());
        assertThat(payroll.corrections("T1", "E1", "2026-10")).hasSize(1);
        assertThat(payroll.corrections("T2", "E1", "2026-10")).isEmpty();
        assertThat(payroll.detail("T1", target.id(), "hr:payroll:read").statusCode())
                .isEqualTo("REVIEW");
        payroll.cancelCorrection(
                "T1",
                paid.id(),
                correction.id(),
                new PayrollActionCommand(correction.revision(), "冲回金额录错"),
                "ACTOR");
        assertThat(payroll.corrections("T1", "E1", "2026-10")).isEmpty();
        var source = payroll.detail("T1", paid.id(), "hr:payroll:read");
        assertThat(source.revision()).isEqualTo(paid.revision());
        assertThat(source.calculation().sourceFingerprint()).isEqualTo("A");
        assertThat(source.correctionsIssued().getFirst().statusCode()).isEqualTo("VOID");
        assertThatThrownBy(
                        () ->
                                payroll.correct(
                                        "T1",
                                        paid.id(),
                                        new PayrollCorrectionCommand(
                                                "CORR-2",
                                                paid.revision(),
                                                "2026-08",
                                                BigDecimal.TEN,
                                                "早于原月"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void correctionsCannotChangeLockedTargetMonth() {
        doReturn(List.of(employee())).when(references).employees(any());
        var source = confirmed();
        var paid =
                payroll.pay(
                        "T1",
                        source.id(),
                        new PayrollPaymentCommand(
                                "PAY",
                                source.revision(),
                                LocalDate.of(2026, 10, 1),
                                new BigDecimal("3040"),
                                "BANK",
                                "发放"),
                        "ACTOR");
        var correction =
                payroll.correct(
                        "T1",
                        paid.id(),
                        new PayrollCorrectionCommand(
                                "C1", paid.revision(), "2026-10", BigDecimal.TEN, "补差"),
                        "ACTOR");
        var target =
                payroll.saveCalculation(
                        "T1", "E1", "2026-10", employee(), calculation("B"), "ACTOR");
        // Stand-in for a confirmed snapshot without claiming the source commission a second time.
        jdbc.update("UPDATE hr_payroll SET status_code='CONFIRMED' WHERE id=?", target.id());
        assertThatThrownBy(
                        () ->
                                payroll.cancelCorrection(
                                        "T1",
                                        paid.id(),
                                        correction.id(),
                                        new PayrollActionCommand(1, "撤销"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                payroll.correct(
                                        "T1",
                                        paid.id(),
                                        new PayrollCorrectionCommand(
                                                "C2",
                                                paid.revision(),
                                                "2026-10",
                                                BigDecimal.TEN,
                                                "补差"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThat(payroll.corrections("T1", "E1", "2026-10")).hasSize(1);
    }

    @Test
    void salarySourceUsesEmployeeProfileWithoutLeakingOtherTenant() {
        jdbc.execute(
                "CREATE TABLE hr_employee(id BIGINT,tenant_id VARCHAR(64),employee_code"
                    + " VARCHAR(50),payroll_category VARCHAR(32),regularized"
                    + " BOOLEAN,regularization_date DATE,salary_effective_from DATE,deleted INT)");
        jdbc.execute(
                "CREATE TABLE hr_employee_profile(tenant_id VARCHAR(64),employee_id"
                        + " BIGINT,regular_salary VARCHAR(64),probation_salary VARCHAR(64))");
        jdbc.update(
                "INSERT INTO hr_employee"
                    + " VALUES(1,'t1','E1','SALES',TRUE,'2026-08-01','2026-01-01',0),(2,'t2','E1','HEADQUARTERS',FALSE,NULL,'2026-01-01',0)");
        jdbc.update(
                "INSERT INTO hr_employee_profile"
                        + " VALUES('t1',1,'5000','3000'),('t2',2,'9999','8888')");
        var value = payroll.salarySource("t1", "E1");
        assertThat(value.payrollCategory()).isEqualTo("SALES");
        assertThat(value.regularized()).isTrue();
        assertThat(value.regularizationDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(value.regularSalary()).isEqualTo("5000");
        assertThat(payroll.salarySource("missing", "E1")).isNull();
    }

    public static LocalDateTime utcNow(int precision) {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T value, DriverManagerDataSource ds) {
        var proxy = new ProxyFactory(value);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @AfterEach
    void closeSchemaConnection() throws Exception {
        schemaConnection.close();
    }

    @AfterAll
    static void close() {
        new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                                "sa",
                                ""))
                .execute("SHUTDOWN");
    }

    PayrollEmployeeView employee() {
        return new PayrollEmployeeView(
                "E1", "测试员工", 10L, "销售部", "测试城市", LocalDate.of(2020, 1, 1), null, "SALES");
    }

    PayrollCalculationView calculation(String fingerprint) {
        return new PayrollCalculationView(
                new BigDecimal("3000"),
                BigDecimal.ZERO,
                new BigDecimal("40"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("3040"),
                new BigDecimal("3040"),
                List.of(),
                List.of(
                        new PayrollCommissionDetailView(
                                "LINE1",
                                "SALES",
                                "SO1",
                                "测试商品",
                                "BOX",
                                BigDecimal.ONE,
                                "RULE1",
                                "测试规则",
                                1,
                                new BigDecimal("40"),
                                new BigDecimal("40"),
                                "")),
                List.of(),
                new PayrollInputCommand(0, null, null, BigDecimal.ZERO, ""),
                fingerprint,
                null,
                null);
    }

    HrExpenseCommand expense(String request, Integer revision, String title) {
        return new HrExpenseCommand(
                request,
                revision,
                title,
                "TRAVEL",
                "E1",
                "CITY",
                "测试城市",
                LocalDate.of(2026, 9, 1),
                new BigDecimal("20"),
                "UNPAID",
                "",
                "修改原因");
    }

    @Test
    void departmentReferencesKeepParentAndSortOrderWithinTenant() {
        jdbc.execute(
                "CREATE TABLE hr_department (id BIGINT, tenant_id VARCHAR(36),"
                        + " parent_id BIGINT, department_name VARCHAR(128), sort_order INT,"
                        + " deleted INT, status_code VARCHAR(16))");
        jdbc.update(
                "INSERT INTO hr_department VALUES"
                        + " (1,'tenant-a',NULL,'公司',0,0,'ACTIVE'),"
                        + " (2,'tenant-a',1,'销售部',20,0,'ACTIVE'),"
                        + " (3,'tenant-a',1,'运营部',10,0,'ACTIVE'),"
                        + " (4,'tenant-b',NULL,'其他租户',0,0,'ACTIVE'),"
                        + " (5,'tenant-a',1,'停用部门',0,0,'DISABLED'),"
                        + " (6,'tenant-a',1,'已删除部门',0,1,'ACTIVE')");

        var rows = payroll.departments("tenant-a", "hr:payroll-rule:read");
        assertThat(rows).extracting(r -> r.id()).containsExactly(1L, 3L, 2L);
        assertThat(rows.getFirst().parentId()).isNull();
        assertThat(rows.get(1).parentId()).isEqualTo(1L);
        assertThat(rows.get(1).name()).isEqualTo("运营部");
        assertThat(rows.get(1).sortOrder()).isEqualTo(10);
    }

    @Test
    void separateTenantsCannotReadEachOthersRows() {
        var saved = expenses.save("T1", null, expense("R1", null, "交通报销"), employee(), "ACTOR");
        assertThat(saved.data().amount()).isEqualByComparingTo("20");
        assertThatThrownBy(() -> expenses.expenseDetail("T2", saved.id(), "hr:expense:read"))
                .isInstanceOf(BusinessException.class);
        assertThat(support.history("T2", List.of(saved.id()))).isEmpty();
    }

    @Test
    void staleExpenseUpdateLeavesAmountAndAuditUnchanged() {
        var saved = expenses.save("T1", null, expense("R1", null, "交通报销"), employee(), "ACTOR");
        expenses.expenseTransition(
                "T1",
                saved.id(),
                new PayrollActionCommand(saved.revision(), ""),
                "CONFIRMED",
                "ACTOR");
        assertThatThrownBy(
                        () ->
                                expenses.save(
                                        "T1",
                                        saved.id(),
                                        expense("R1", saved.revision(), "过期修改"),
                                        employee(),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThat(expenses.expenseDetail("T1", saved.id(), "hr:expense:read").data().title())
                .isEqualTo("交通报销");
        assertThat(support.history("T1", List.of(saved.id()))).hasSize(2);
    }

    @Test
    void concurrentCreateWithSameRequestCreatesOneExpenseAndOneAudit() throws Exception {
        var command = expense("RETRY", null, "交通报销");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<HrExpenseDetailView> call =
                    () -> {
                        start.await();
                        return expenses.save("T1", null, command, employee(), "ACTOR");
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).id())
                    .isEqualTo(b.get(10, TimeUnit.SECONDS).id());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_expense", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_payroll_audit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void monthlyAdjustmentInvalidatesReadyCalculationAndAppearsInHistory() {
        var saved =
                payroll.saveCalculation(
                        "T1", "E1", "2026-09", employee(), calculation("A"), "ACTOR");
        var adjusted =
                payroll.saveInput(
                        "T1",
                        "E1",
                        "2026-09",
                        new PayrollInputCommand(
                                0,
                                new BigDecimal("3100"),
                                null,
                                BigDecimal.ZERO,
                                "补充底薪与补贴",
                                new BigDecimal("375"),
                                20L),
                        "ACTOR");
        assertThat(adjusted.revision()).isEqualTo(1);
        assertThat(adjusted.allowanceOverride()).isEqualByComparingTo("375");
        assertThat(adjusted.departmentOverrideId()).isEqualTo(20L);
        assertThat(payroll.input("T1", "E1", "2026-08").departmentOverrideId()).isNull();
        assertThat(payroll.input("T2", "E1", "2026-09").departmentOverrideId()).isNull();
        assertThat(payroll.detail("T1", saved.id(), "hr:payroll:read").statusCode())
                .isEqualTo("REVIEW");
        assertThat(payroll.history("T1", saved.id()))
                .extracting(PayrollAuditView::action)
                .contains("INPUT_BEFORE", "INPUT_SAVE");
        assertThatThrownBy(
                        () ->
                                payroll.saveInput(
                                        "T1",
                                        "E1",
                                        "2026-09",
                                        new PayrollInputCommand(
                                                0, null, null, BigDecimal.ZERO, "过期调整"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void confirmedPayrollIsImmutableAndCommissionCanOnlyBeClaimedOnce() {
        var saved =
                payroll.saveCalculation(
                        "T1", "E1", "2026-09", employee(), calculation("A"), "ACTOR");
        var confirmed =
                payroll.transition(
                        "T1",
                        saved.id(),
                        new PayrollActionCommand(saved.revision(), "确认"),
                        "CONFIRMED",
                        "ACTOR");
        assertThat(confirmed.statusCode()).isEqualTo("CONFIRMED");
        assertThat(payroll.released("T1")).containsExactly("LINE1|SALES");
        assertThatThrownBy(
                        () ->
                                payroll.saveCalculation(
                                        "T1",
                                        "E1",
                                        "2026-09",
                                        employee(),
                                        calculation("B"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                payroll.saveInput(
                                        "T1",
                                        "E1",
                                        "2026-09",
                                        new PayrollInputCommand(
                                                0, null, null, BigDecimal.ZERO, "修改"),
                                        "ACTOR"))
                .isInstanceOf(BusinessException.class);
        var second =
                payroll.saveCalculation(
                        "T1", "E1", "2026-10", employee(), calculation("B"), "ACTOR");
        assertThatThrownBy(
                        () ->
                                payroll.transition(
                                        "T1",
                                        second.id(),
                                        new PayrollActionCommand(second.revision(), "重复领取"),
                                        "CONFIRMED",
                                        "ACTOR"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(payroll.detail("T1", second.id(), "hr:payroll:read").statusCode())
                .isEqualTo("READY");
        assertThat(payroll.released("T2")).isEmpty();
    }
}
