package com.rigour.hr;

import static org.assertj.core.api.Assertions.*;

import com.rigour.hr.api.controller.AnalyticsSourceSnapshotController;
import com.rigour.hr.infrastructure.persistence.repository.JdbcAnalyticsSourceSnapshotStore;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import java.util.*;

/** 真实 MySQL 验证专用导出契约、租户隔离、变化版本及浏览器拒绝。 */
@Testcontainers
class AnalyticsSourceSnapshotContractTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4").withDatabaseName("source_contract");

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void exportIsTenantScopedVersionedAndServiceOnly() {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        String tenant = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();

        jdbc.execute(
                "CREATE TABLE hr_employee(id BIGINT,tenant_id VARCHAR(64),employee_code"
                    + " VARCHAR(50),employee_name VARCHAR(128),employment_status"
                    + " VARCHAR(32),city_name VARCHAR(160),primary_position_code"
                    + " VARCHAR(50),primary_position_name_snapshot VARCHAR(120),job_category"
                    + " VARCHAR(80),department_name_snapshot VARCHAR(128),department_id"
                    + " BIGINT,entry_date DATETIME(6),leave_date DATETIME(6),revision"
                    + " INT,access_version BIGINT,updated_time DATETIME(6),deleted INT)");
        jdbc.execute(
                "CREATE TABLE hr_position(tenant_id VARCHAR(64),position_code"
                    + " VARCHAR(50),position_name VARCHAR(120),deleted INT)");
        jdbc.execute(
                "CREATE TABLE hr_department(tenant_id VARCHAR(64),id BIGINT,department_name"
                    + " VARCHAR(128),deleted INT)");
        jdbc.execute(
                "CREATE TABLE hr_department_closure(tenant_id VARCHAR(64),ancestor_id"
                    + " BIGINT,descendant_id BIGINT)");
        jdbc.update(
                "INSERT INTO"
                    + " hr_employee(id,tenant_id,employee_code,employee_name,employment_status,department_id,revision,access_version,deleted)"
                    + " VALUES(1,?,'A','张三','ACTIVE',10,1,1,0),(2,?,'B','其他租户','ACTIVE',10,1,1,0)",
                tenant,
                other);
        jdbc.update("INSERT INTO hr_department VALUES(?,10,'杭州部门',0)", tenant);
        jdbc.update("INSERT INTO hr_department_closure VALUES(?,1,10),(?,10,10)", tenant, tenant);

        var store = new JdbcAnalyticsSourceSnapshotStore(jdbc);
        var controller = new AnalyticsSourceSnapshotController(store);
        UUID user = UUID.randomUUID();
        String dataset = "HR_EMPLOYEE";
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.fromString(tenant),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:analytics:source-read")));
        assertThatThrownBy(() -> controller.page(dataset, ""))
                .isInstanceOf(AuthorizationDeniedException.class);
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "SERVICE",
                        user,
                        UUID.fromString(tenant),
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:analytics:source-read")));
        var page = controller.page(dataset, "");
        String version = page.version();
        assertThatThrownBy(() -> controller.page("UNREGISTERED", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.page(dataset, "1 OR 1=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.page(tenant, dataset, page.items().getFirst().get("id")).items())
                .isEmpty();

        assertThat(page.items())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("employee_name")).isEqualTo("张三");
                            assertThat(row.get("department_path")).contains("10");
                            assertThat(row).doesNotContainKeys("phone", "id_card_no");
                        });
        jdbc.update("INSERT INTO hr_department_closure VALUES(?,2,10)", tenant);
        assertThat(store.version(tenant, dataset)).isNotEqualTo(version);
        jdbc.update("UPDATE hr_employee SET deleted=1 WHERE tenant_id=?", tenant);
        assertThat(store.page(tenant, dataset, "").items()).isEmpty();
    }
}
