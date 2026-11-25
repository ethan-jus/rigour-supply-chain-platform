package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.TargetSettingsModels.*;
import com.rigour.hr.application.service.TargetSettingsService;
import com.rigour.shared.context.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Testcontainers(disabledWithoutDocker = true)
class TargetMonthArchiveMySqlTest {
    @Container static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4").withDatabaseName("hr_month_archive");
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    private TargetSettingsService service(HrDataScope scope) {
        var raw = new TargetSettingsService(new JdbcTargetSettingsStore(jdbc, scope),
                Clock.fixed(Instant.parse("2026-10-31T16:30:00Z"), ZoneOffset.UTC));
        var proxy = new ProxyFactory(raw);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (TargetSettingsService) proxy.getProxy();
    }

    private void authorizeTenant() {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null,
                UUID.randomUUID(), 1, 1, 1, Set.of(), Set.of("hr:targets:read", "hr:targets:write")));
    }

    @Test
    void liveRosterAndArchiveUseDifferentOrganizationDatesWithoutChangingBiValues() {
        String id = tenant.toString();
        long root = department(id, "SALES", "销售部", null);
        long city = department(id, "CD", "成都市", root);
        long north = department(id, "NORTH", "北区", city);
        long south = department(id, "SOUTH", "南区", city);
        jdbc.update("""
            INSERT INTO hr_department_closure(tenant_id,ancestor_id,descendant_id,depth)
            VALUES(?,?,?,0),(?,?,?,1),(?,?,?,2),(?,?,?,2)
            """, id,root,root,id,root,city,id,root,north,id,root,south);
        jdbc.update("""
            INSERT INTO hr_department_parent_history(tenant_id,department_id,effective_at,
                parent_id,department_name,department_code,status_code)
            SELECT tenant_id,id,'2026-09-01',parent_id,department_name,department_code,status_code
            FROM hr_department WHERE tenant_id=?
            """, id);
        jdbc.update("""
            INSERT INTO hr_position(tenant_id,position_code,position_name,status_code)
            VALUES(?,'SALES','销售','ACTIVE')
            """, id);
        jdbc.update("""
            INSERT INTO hr_employee(tenant_id,employee_code,employee_name,department_id,employment_status)
            VALUES(?,'MOVE','调岗销售',?,'ACTIVE'),(?,'LEFT','离职销售',?,'LEFT'),(?,'NEW','新销售',?,'ACTIVE')
            """, id,south,id,north,id,south);
        jdbc.update("""
            INSERT INTO hr_employee_assignment(tenant_id,employee_code,department_id,department_name_snapshot,
                position_code,position_name_snapshot,effective_from,effective_to,actor_id)
            VALUES(?,'MOVE',?,'北区','SALES','销售','2026-09-01','2026-10-31 16:00:00','fixture'),
                  (?,'MOVE',?,'南区','SALES','销售','2026-10-31 16:00:00',NULL,'fixture'),
                  (?,'LEFT',?,'北区','SALES','销售','2026-09-01','2026-10-31 16:00:00','fixture'),
                  (?,'NEW',?,'南区','SALES','销售','2026-10-31 16:00:00',NULL,'fixture')
            """, id,north,id,south,id,north,id,south);
        jdbc.update("""
            INSERT INTO hr_business_target(tenant_id,target_month,dimension_type,dimension_code,
                dimension_name,metric_code,target_value,revision)
            VALUES(?,'2026-10-01','SALES_OWNER','MOVE','调岗销售','SALES_AMOUNT',0,2),
                  (?,'2026-10-01','SALES_OWNER','LEFT','离职销售','RECEIPT_AMOUNT',8300,1),
                  (?,'2026-10-01','SALES_OWNER','LEGACY','历史销售','SALES_AMOUNT',12000,1),
                  (?,'2026-11-01','SALES_OWNER','MOVE','调岗销售','SALES_AMOUNT',51000,1)
            """, id,id,id,id);

        var scope = mock(HrDataScope.class);
        var all = new HrDataScope.Predicate("1=1", List.of());
        when(scope.employeePredicate(anyString(), eq(""))).thenReturn(all);
        when(scope.departmentPredicate(anyString())).thenReturn(all);
        when(scope.fullEmployeeDirectory("hr:targets:read")).thenReturn(true);
        var service = service(scope);
        authorizeTenant();

        for (String month : List.of("2026-11", "2026-12")) {
            var live = service.settings(month);
            assertThat(live.archived()).isFalse();
            assertThat(live.subjects()).extracting(Subject::code).contains("NEW").doesNotContain("LEFT");
            assertThat(live.subjects()).filteredOn(s -> s.code().equals("MOVE"))
                    .singleElement().extracting(Subject::parentCode).isEqualTo("SOUTH");
            assertThat(live.subjects()).filteredOn(s -> s.code().equals("CD"))
                    .singleElement().extracting(Subject::parentCode).isNull();
            assertThat(live.subjects()).filteredOn(s -> s.code().equals("SOUTH"))
                    .singleElement().extracting(Subject::parentCode).isEqualTo("CD");
            assertThat(sales(live, "MOVE").value()).isEqualByComparingTo(month.equals("2026-11") ? "51000" : "40000");
        }

        var historical = service.settings("2026-10");
        assertThat(historical.archived()).isTrue();
        assertThat(historical.subjects()).extracting(Subject::code).doesNotContain("NEW").contains("LEFT");
        assertThat(historical.subjects()).filteredOn(s -> s.code().equals("MOVE"))
                .singleElement().extracting(Subject::parentCode).isEqualTo("NORTH");
        assertThat(historical.subjects()).filteredOn(s -> s.code().equals("LEGACY"))
                .singleElement().satisfies(s -> {
                    assertThat(s.parentCode()).isNull();
                    assertThat(s.historicalPlacementUnknown()).isTrue();
                });
        assertThat(historical.targets()).filteredOn(t -> t.code().equals("LEGACY")).hasSize(1);
        assertThat(sales(historical, "MOVE").value()).isEqualByComparingTo("0");
        assertThat(historical.subjects()).noneMatch(Subject::writable);

        jdbc.update("UPDATE hr_department SET department_name='南二区' WHERE id=?", south);
        assertThat(service.settings("2026-10")).isEqualTo(historical);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_business_target_month_archive WHERE tenant_id=?", Integer.class, id))
                .isEqualTo(1);
        assertThatThrownBy(() -> service.save(new Batch("2026-10",
                List.of(new Change("SALES_OWNER", "MOVE", "SALES_AMOUNT", BigDecimal.ONE, 2)), "fixture")))
                .hasMessageContaining("历史月份");
        assertThatThrownBy(() -> service.save(new Batch("2026-11",
                List.of(new Change("SALES_OWNER", "LEFT", "SALES_AMOUNT", BigDecimal.ONE, 0)), "fixture")))
                .hasMessageContaining("已离职");

        when(scope.fullEmployeeDirectory("hr:targets:read")).thenReturn(false);
        when(scope.employeePredicate(eq("hr:targets:read"), eq("")))
                .thenReturn(new HrDataScope.Predicate("department_id=?", List.of(south)));
        when(scope.departmentPredicate("hr:targets:read"))
                .thenReturn(new HrDataScope.Predicate("id=?", List.of(south)));
        var restricted = service.settings("2026-10");
        assertThat(restricted.subjects()).extracting(Subject::code).containsExactlyInAnyOrder("SOUTH", "MOVE");
        assertThat(restricted.subjects()).filteredOn(s -> s.code().equals("MOVE"))
                .singleElement().satisfies(s -> {
                    assertThat(s.parentCode()).isNull();
                    assertThat(s.departmentName()).isNull();
                });
        assertThatThrownBy(() -> service.history("2026-10", "SALES_OWNER", "LEFT"))
                .isInstanceOf(AuthorizationDeniedException.class);

        TestAuthorizationContext.set(new CallerIdentity("SERVICE", user, tenant, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("hr:targets:service-read")));
        assertThat(service.values("2026-11", "2026-11")).anyMatch(t -> t.code().equals("LEFT"));
        assertThat(service.businessValues("MOVE", "2026-10", "2026-10").salesTargetAmount())
                .isEqualByComparingTo("0");
    }

    private long department(String tenant, String code, String name, Long parent) {
        jdbc.update("""
            INSERT INTO hr_department(tenant_id,department_code,department_name,parent_id)
            VALUES(?,?,?,?)
            """, tenant,code,name,parent);
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM hr_department WHERE tenant_id=? AND department_code=?", Long.class, tenant, code));
    }

    private static Target sales(Settings settings, String code) {
        return settings.targets().stream().filter(t -> t.code().equals(code) && t.metric().equals("SALES_AMOUNT"))
                .findFirst().orElseThrow();
    }
}
