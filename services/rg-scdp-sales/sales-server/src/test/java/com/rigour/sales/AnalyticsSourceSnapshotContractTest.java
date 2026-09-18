package com.rigour.sales;

import static org.assertj.core.api.Assertions.*;

import com.rigour.sales.api.controller.AnalyticsSourceSnapshotController;
import com.rigour.sales.infrastructure.persistence.repository.JdbcAnalyticsSourceSnapshotStore;
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
                "CREATE TABLE temp_sales_checkin_submission(id BINARY(16),tenant_id"
                    + " BINARY(16),store_id BINARY(16),salesperson_id BINARY(16),status"
                    + " VARCHAR(32),deletion_state VARCHAR(32),submitted_at"
                    + " DATETIME(6),review_status VARCHAR(32))");
        jdbc.execute(
                "CREATE TABLE temp_sales_checkin_store(id BINARY(16),tenant_id BINARY(16),city"
                    + " VARCHAR(160))");
        jdbc.execute(
                "CREATE TABLE temp_sales_checkin_employee_link(tenant_id BINARY(16),salesperson_id"
                    + " BINARY(16),employee_code VARCHAR(128))");
        jdbc.execute(
                "CREATE TABLE temp_sales_checkin_customer_link(tenant_id BINARY(16),store_id"
                    + " BINARY(16),customer_id BIGINT,customer_code VARCHAR(128))");
        String shop = UUID.randomUUID().toString(), person = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO temp_sales_checkin_store"
                    + " VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'杭州'),(UUID_TO_BIN(?),UUID_TO_BIN(?),'宁波')",
                shop,
                tenant,
                shop,
                other);
        for (String status : List.of("SUBMITTED", "DRAFT"))
            jdbc.update(
                    "INSERT INTO temp_sales_checkin_submission"
                        + " VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,'NONE',UTC_TIMESTAMP(6),'PENDING')",
                    UUID.randomUUID().toString(),
                    tenant,
                    shop,
                    person,
                    status);
        jdbc.update(
                "INSERT INTO temp_sales_checkin_submission"
                    + " VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'SUBMITTED','NONE',UTC_TIMESTAMP(6),'PENDING')",
                UUID.randomUUID().toString(),
                other,
                shop,
                person);
        jdbc.update(
                "INSERT INTO temp_sales_checkin_employee_link"
                    + " VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'EMP_A'),(UUID_TO_BIN(?),UUID_TO_BIN(?),'EMP_OTHER')",
                tenant,
                person,
                other,
                person);
        jdbc.update(
                "INSERT INTO temp_sales_checkin_customer_link"
                    + " VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),9007199254740993,'C1')",
                tenant,
                shop);

        var store = new JdbcAnalyticsSourceSnapshotStore(jdbc);
        var controller = new AnalyticsSourceSnapshotController(store);
        UUID user = UUID.randomUUID();
        String dataset = "SALES_SUBMITTED_VISIT";
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
                        Set.of("sales:analytics:source-read")));
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
                        Set.of("sales:analytics:source-read")));
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
                            assertThat(row.get("owner_staff_code")).isEqualTo("EMP_A");
                            assertThat(row.get("customer_id")).isEqualTo("9007199254740993");
                            assertThat(row.get("city_name")).isEqualTo("杭州");
                            assertThat(row).doesNotContainKeys("phone", "audio", "screenshot");
                        });
        jdbc.update(
                "UPDATE temp_sales_checkin_employee_link SET employee_code='EMP_CHANGED' WHERE"
                    + " tenant_id=UUID_TO_BIN(?)",
                tenant);
        assertThat(store.version(tenant, dataset)).isNotEqualTo(version);
        jdbc.update(
                "UPDATE temp_sales_checkin_submission SET deletion_state='DELETED' WHERE"
                    + " tenant_id=UUID_TO_BIN(?)",
                tenant);
        assertThat(store.page(tenant, dataset, "").items()).isEmpty();
    }
}
