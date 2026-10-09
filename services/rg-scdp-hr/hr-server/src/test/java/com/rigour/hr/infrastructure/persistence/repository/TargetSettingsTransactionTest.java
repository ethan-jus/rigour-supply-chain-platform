package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.TargetSettingsModels.*;
import com.rigour.hr.application.service.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class TargetSettingsTransactionTest {
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource ds;
    private JdbcTargetSettingsStore store;
    private TargetSettingsService service;
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    private final Subject subject = new Subject("CITY", "BJ", "北京", "BJ", "北京", null, null, true);

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:targets_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        execute(
                Files.readString(
                        Path.of("src/main/resources/db/migration/V9__hr_business_targets.sql")));
        store = spy(new JdbcTargetSettingsStore(jdbc, mock(HrDataScope.class)));
        doReturn(List.of(subject)).when(store).subjects(tenant.toString());
        doReturn(Set.of("CITY:BJ")).when(store).permittedSubjects(anyString(), anyString());
        var raw =
                new TargetSettingsService(
                        store, Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC));
        var proxy = new ProxyFactory(raw);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        service = (TargetSettingsService) proxy.getProxy();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of(),
                        Set.of(TargetSettingsService.READ, TargetSettingsService.WRITE)));
    }

    void execute(String sql) {
        jdbc.execute(
                sql.replaceAll(
                        "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_[a-z0-9_]+", ""));
    }

    @AfterEach
    void close() {
        TestAuthorizationContext.clear();
        jdbc.execute("SHUTDOWN");
        ds.destroy();
    }

    Change c(String metric, String value, int version) {
        return new Change(
                "CITY", "BJ", metric, value == null ? null : new BigDecimal(value), version);
    }

    void save(Change... changes) {
        service.save(new Batch("2026-10", List.of(changes), "测试调整"));
    }

    @Test
    void persistenceZeroRevisionAndHistoryRemainTenantScoped() {
        save(c("SALES_AMOUNT", "120000", 0), c("RECEIPT_AMOUNT", "0", 0));
        save(c("SALES_AMOUNT", "8", 1));
        var target =
                service.settings("2026-10").targets().stream()
                        .filter(o -> o.metric().equals("SALES_AMOUNT"))
                        .findFirst()
                        .orElseThrow();
        assertThat(target.value()).isEqualByComparingTo("8");
        assertThat(target.revision()).isEqualTo(2);
        assertThatThrownBy(() -> save(c("SALES_AMOUNT", "9", 1)))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.history("2026-10", "CITY", "BJ")).hasSize(3);
        assertThat(store.targets(UUID.randomUUID().toString(), "2026-10", "2026-10")).isEmpty();
        assertThat(store.history(UUID.randomUUID().toString(), "2026-10", "CITY", "BJ")).isEmpty();
    }

    @Test
    void lateRevisionConflictRollsBackEarlierItemsAndAudit() {
        save(c("RECEIPT_AMOUNT", "500", 0));
        assertThatThrownBy(() -> save(c("SALES_AMOUNT", "999", 0), c("RECEIPT_AMOUNT", "600", 0)))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.targets(tenant.toString(), "2026-10", "2026-10"))
                .singleElement()
                .extracting(Target::value)
                .isEqualTo(new BigDecimal("500.000000"));
        assertThat(service.history("2026-10", "CITY", "BJ")).hasSize(1);
    }

    @Test
    void auditFailureRollsBackEntireBatch() {
        jdbc.execute(
                "ALTER TABLE hr_business_target_event ADD CONSTRAINT reject_event"
                    + " CHECK(target_value<>13)");
        assertThatThrownBy(() -> save(c("SALES_AMOUNT", "10", 0), c("RECEIPT_AMOUNT", "13", 0)))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.targets(tenant.toString(), "2026-10", "2026-10")).isEmpty();
        assertThat(service.history("2026-10", "CITY", "BJ")).isEmpty();
    }

    @Test
    void newNestedDepartmentAndEmployeeAppearImmediatelyWithoutInitializingTargets() {
        jdbc.execute(
                "CREATE TABLE hr_department(id BIGINT,tenant_id VARCHAR(64),department_code"
                    + " VARCHAR(64),department_name VARCHAR(64),deleted INT,status_code"
                    + " VARCHAR(20))");
        jdbc.execute(
                "CREATE TABLE hr_department_closure(tenant_id VARCHAR(64),ancestor_id"
                    + " BIGINT,descendant_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE hr_employee(id BIGINT,tenant_id VARCHAR(64),employee_code"
                    + " VARCHAR(64),employee_name VARCHAR(64),department_id"
                    + " BIGINT,employment_status VARCHAR(20),deleted INT)");
        jdbc.update(
                "INSERT INTO hr_department"
                    + " VALUES(1,?,'SALES','销售部',0,'ACTIVE'),(2,?,'BJ','北京',0,'ACTIVE'),(9,?,'HR','人事部',0,'ACTIVE')",
                tenant.toString(),
                tenant.toString(),
                tenant.toString());
        jdbc.update(
                "INSERT INTO hr_department_closure VALUES(?,1,1),(?,1,2)",
                tenant.toString(),
                tenant.toString());
        var realStore = new JdbcTargetSettingsStore(jdbc, mock(HrDataScope.class));
        assertThat(realStore.subjects(tenant.toString()))
                .extracting(Subject::code)
                .containsExactly("BJ");
        jdbc.update(
                "INSERT INTO hr_department VALUES(3,?,'BJ-1','北京一部',0,'ACTIVE')",
                tenant.toString());
        jdbc.update(
                "INSERT INTO hr_department_closure VALUES(?,1,3),(?,2,3)",
                tenant.toString(),
                tenant.toString());
        jdbc.update(
                "INSERT INTO hr_employee"
                    + " VALUES(1,?,'NEW','新销售',3,'ACTIVE',0),(2,?,'ADMIN','人事',9,'ACTIVE',0),(3,?,'DELETED','删除员工',3,'ACTIVE',1),(4,?,'OTHER','其他租户销售',3,'ACTIVE',0)",
                tenant.toString(),
                tenant.toString(),
                tenant.toString(),
                UUID.randomUUID().toString());
        assertThat(realStore.subjects(tenant.toString()))
                .extracting(Subject::code)
                .containsExactly("BJ", "BJ-1", "NEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_business_target", Integer.class))
                .isZero();
    }
}
