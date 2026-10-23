package com.rigour.analytics.infrastructure.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Minimal city projection used by isolated H2 query fixtures; MySQL tests use the real dialect. */
public final class OperatingCityTestSchema {
    private OperatingCityTestSchema() {}

    public static void install(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bi_sales_contact_city_dim (
                    tenant_id VARCHAR(64), region_code VARCHAR(64), city_name VARCHAR(160),
                    department_id BIGINT, department_path VARCHAR(1000), source_region_code VARCHAR(64))
                """);
        jdbc.execute("ALTER TABLE bi_sales_contact_city_dim ADD COLUMN IF NOT EXISTS department_id BIGINT");
        jdbc.execute("ALTER TABLE bi_sales_contact_city_dim ADD COLUMN IF NOT EXISTS department_path VARCHAR(1000)");
        jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_LENGTH FOR '" + OperatingCityTestSchema.class.getName() + ".jsonLength'");
    }

    public static Integer jsonLength(String value) {
        if (value == null) return null;
        var node = JsonMapper.builder().build().readTree(value);
        return node.isContainerNode() ? node.size() : 1;
    }
}
