package com.rigour.tenant.iam;

import org.junit.jupiter.api.Test;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.ApplicationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantIamServiceApplicationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_iam")
            .withUsername("rigour_iam_test")
            .withPassword("rigour_iam_test_password");

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

    @Autowired
    private ApplicationMapper applicationMapper;

    @Test
    void contextLoadsAndMigratesIamSchema() {
        assertCount("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", 6);
        assertCount("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name LIKE 'iam\\_%'", 22);
        assertCount("SELECT COUNT(*) FROM iam_application", 5);
        assertCount("SELECT COUNT(*) FROM iam_resource", 70);
        assertCount("SELECT COUNT(permission_code) FROM iam_resource", 24);
        assertCount("SELECT COUNT(*) FROM iam_package_resource", 47);
        org.assertj.core.api.Assertions.assertThat(applicationMapper.selectById(
                        UUID.fromString("019facf1-0000-7000-8000-000000000003")))
                .extracting("appCode")
                .isEqualTo("SUPPLY_CHAIN");
        org.assertj.core.api.Assertions.assertThat(applicationMapper.selectActiveByScope("TENANT"))
                .extracting("appCode")
                .containsExactly("SYSTEM_ADMIN", "SUPPLY_CHAIN");
    }

    private void assertCount(String sql, int expected) {
        Integer actual = jdbcTemplate.queryForObject(sql, Integer.class);
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }
}
