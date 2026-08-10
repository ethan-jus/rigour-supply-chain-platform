package com.rigour.sales;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

    /** 使用一次性MySQL 8.4验证Sales Work迁移，不连接共享DEV。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SalesWorkMigrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work")
            .withUsername("rigour_sales_work_test")
            .withPassword("rigour_sales_work_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migratesCompleteV1Schema() {
        Integer migrationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class);
        Integer tableCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema=DATABASE()
                   AND (table_name LIKE 'sales\\_%' OR table_name LIKE 'crm\\_%')
                """, Integer.class);
        Integer editableStoreTableCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_name='sales_store'
                """, Integer.class);

        assertThat(migrationCount).isEqualTo(7);
        assertThat(tableCount).isEqualTo(32);
        assertThat(editableStoreTableCount).isZero();
    }
}
