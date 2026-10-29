package com.rigour.sales;

import static org.assertj.core.api.Assertions.*;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SalesEmployeeProfileMigrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("sales_profile_upgrade").withUsername("test").withPassword("test");

    @Test void upgradesV27WithoutRewritingLegacyIdentitiesOrHistory() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("27").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        byte[] tenant = bin(), employee = bin(), user = bin();
        jdbc.update("""
                INSERT INTO sales_profile (id,tenant_id,employee_id,sales_no,status,effective_from,created_at,updated_at)
                VALUES (?,?,?,'LEGACY','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, bin(), tenant, employee);
        jdbc.update("""
                INSERT INTO sales_identity_projection (id,tenant_id,platform_user_id,employee_id,status,effective_from,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, bin(), tenant, user, employee);
        var checksums = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
        var migration = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration", "classpath:db/bootstrap").target("28").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        migration.validate();
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'28' ORDER BY installed_rank"))
                .isEqualTo(checksums);
        assertThat(jdbc.queryForObject("SELECT employee_id FROM sales_profile WHERE tenant_id=?", byte[].class, tenant)).isEqualTo(employee);
        assertThat(jdbc.queryForObject("SELECT employee_id FROM sales_identity_projection WHERE tenant_id=?", byte[].class, tenant)).isEqualTo(employee);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sales_profile (id,tenant_id,platform_user_id,sales_no,hr_employee_code,status,effective_from,created_at,updated_at)
                VALUES (?,?,?,'MISSING-HR','MISSING-HR','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, bin(), tenant, bin())).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("ck_sales_profile_employee_reference");
        assertThat(jdbc.queryForObject("""
                SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE()
                  AND table_name='sales_profile' AND column_name='hr_employee_code'
                """, String.class)).isEqualTo("utf8mb4_general_ci");
    }
    private static byte[] bin() { return SalesUuidCodec.encode(UUID.randomUUID()); }
}
