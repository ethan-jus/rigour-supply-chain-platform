package com.rigour.merchant.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL 验证创建人修正范围、租户关联及 ON UPDATE 时间戳保护。 */
@Testcontainers(disabledWithoutDocker = true)
class FeishuSystemCreatorMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final String LEGACY_ACTOR = UUID.nameUUIDFromBytes(
            "rigour-integration-feishu-import-service".getBytes(StandardCharsets.UTF_8)).toString();

    @Test
    void repairsOnlyLegacyFeishuCreatorsAndPreservesOtherAuditFields() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(ds);
        var tables = List.of("crm_customer", "crm_party", "crm_customer_area", "crm_customer_type",
                "crm_contact", "crm_address", "crm_party_role", "crm_customer_profile");
        for (String table : tables) {
            jdbc.execute("CREATE TABLE " + table + """
                    (tenant_id VARCHAR(36), id INT, party_id INT, record_origin VARCHAR(24),
                     source_system_code VARCHAR(32), created_by VARCHAR(50), created_time DATETIME(6),
                     updated_by VARCHAR(50), updated_time DATETIME(6) ON UPDATE CURRENT_TIMESTAMP(6),
                     revision INT, PRIMARY KEY (tenant_id,id))
                    """);
            insert(jdbc, table, "T", 1, "FEISHU", LEGACY_ACTOR);
            insert(jdbc, table, "T", 2, "FEISHU", "human-creator");
            insert(jdbc, table, "T", 3, "DHB", LEGACY_ACTOR);
            insert(jdbc, table, "T", 4, "FEISHU", "SYSTEM");
            insert(jdbc, table, "T", 5, "FEISHU", LEGACY_ACTOR);
        }
        // 同一 party_id 在另一租户属于飞书，不能修正当前租户的人工主体关联记录。
        jdbc.update("UPDATE crm_party SET record_origin='MANUAL' WHERE tenant_id='T' AND id=5");
        insert(jdbc, "crm_party", "OTHER", 5, "FEISHU", LEGACY_ACTOR);
        var before = tables.stream().collect(java.util.stream.Collectors.toMap(
                table -> table, table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY tenant_id,id")));
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V15__crm_feishu_import_system_creator.sql"));
        migration.execute(ds);
        for (String table : tables) {
            var after = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY tenant_id,id");
            for (int i = 0; i < after.size(); i++) {
                var original = before.get(table).get(i);
                var row = after.get(i);
                int id = ((Number) row.get("id")).intValue();
                boolean protectedParty = id == 5 && "T".equals(row.get("tenant_id"))
                        && List.of("crm_party", "crm_party_role", "crm_customer_profile").contains(table);
                String expected = (id == 1 || id == 5) && !protectedParty
                        ? "SYSTEM" : (String) original.get("created_by");
                assertThat(row.get("created_by")).as(table + ":" + id).isEqualTo(expected);
                row.remove("created_by");
                var unchanged = new java.util.HashMap<>(original);
                unchanged.remove("created_by");
                assertThat(row).as("创建人以外的字段 " + table + ":" + id).isEqualTo(unchanged);
            }
        }
        var firstRun = jdbc.queryForList("SELECT * FROM crm_customer ORDER BY tenant_id,id");
        migration.execute(ds);
        assertThat(jdbc.queryForList("SELECT * FROM crm_customer ORDER BY tenant_id,id")).isEqualTo(firstRun);
    }

    private static void insert(JdbcTemplate jdbc, String table, String tenant, int id, String source, String actor) {
        jdbc.update("INSERT INTO " + table + " VALUES (?,?,?,?,?,?, '2026-09-08 06:03:46.668217',"
                + "'later-human-edit', '2026-09-09 21:27:21.459514', 7)", tenant, id, id, source, source, actor);
    }
}
