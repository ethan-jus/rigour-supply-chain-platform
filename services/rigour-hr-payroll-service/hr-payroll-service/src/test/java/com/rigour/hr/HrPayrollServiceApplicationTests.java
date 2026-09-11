package com.rigour.hr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HrPayrollServiceApplicationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_hr")
            .withUsername("rigour_hr_test")
            .withPassword("rigour_hr_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAndMigratesHrTables() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name IN (
                   'hr_employee', 'hr_position', 'hr_employee_source_binding'
                 )
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'hr_employee'
                """, String.class)).contains(
                "employee_code", "employment_status", "job_category",
                "primary_position_code", "region_name", "city_name",
                "source_system", "source_document_no", "source_payload_json");
    }
}
