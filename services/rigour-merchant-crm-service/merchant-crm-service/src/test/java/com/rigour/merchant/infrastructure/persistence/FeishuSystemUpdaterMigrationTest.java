package com.rigour.merchant.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证系统更新人的修正范围，同时保护人工审计、租户隔离及历史时间。 */
@Testcontainers(disabledWithoutDocker = true)
class FeishuSystemUpdaterMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final String LEGACY_ACTOR = UUID.nameUUIDFromBytes(
            "rigour-integration-feishu-import-service".getBytes(StandardCharsets.UTF_8)).toString();
    private static final String REPAIR_ACTOR = "codex-bi-owner-code-repair";

    @Test
    void repairsKnownSystemUpdatersWithoutChangingHumanAuditsOrOtherFields() {
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
            insert(jdbc, table, "T", 2, "FEISHU", "human-updater");
            insert(jdbc, table, "T", 3, "DHB", LEGACY_ACTOR);
            insert(jdbc, table, "T", 4, "FEISHU", "SYSTEM");
            insert(jdbc, table, "T", 5, "FEISHU", LEGACY_ACTOR);
            insert(jdbc, table, "T", 6, "FEISHU", REPAIR_ACTOR);
            insert(jdbc, table, "T", 7, "DHB", REPAIR_ACTOR);
            insert(jdbc, table, "T", 8, "FEISHU", "unrecognized-service");
        }
        // 别的租户有同号飞书主体，也不能修改本租户的非飞书主体及其关联记录。
        jdbc.update("UPDATE crm_party SET record_origin='MANUAL' WHERE tenant_id='T' AND id=5");
        insert(jdbc, "crm_party", "OTHER", 5, "FEISHU", LEGACY_ACTOR);
        var before = tables.stream().collect(Collectors.toMap(
                table -> table, table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY tenant_id,id")));
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V16__crm_feishu_import_system_updater.sql"));
        migration.execute(ds);
        for (String table : tables) {
            var after = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY tenant_id,id");
            assertThat(after).hasSameSizeAs(before.get(table));
            for (int i = 0; i < after.size(); i++) {
                var original = before.get(table).get(i);
                var row = after.get(i);
                int id = ((Number) row.get("id")).intValue();
                boolean protectedParty = id == 5 && "T".equals(row.get("tenant_id"))
                        && List.of("crm_party", "crm_party_role", "crm_customer_profile").contains(table);
                boolean repair = ((id == 1 || id == 5) && !protectedParty)
                        || (id == 6 && "crm_customer".equals(table));
                assertThat(row.get("updated_by")).as(table + ":" + row.get("tenant_id") + ":" + id)
                        .isEqualTo(repair ? "SYSTEM" : original.get("updated_by"));
                row.remove("updated_by");
                var unchanged = new HashMap<>(original);
                unchanged.remove("updated_by");
                assertThat(row).as("更新人以外的字段 " + table + ":" + id).isEqualTo(unchanged);
            }
        }
        var firstRun = tables.stream().collect(Collectors.toMap(
                table -> table, table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY tenant_id,id")));
        migration.execute(ds);
        for (String table : tables) {
            assertThat(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY tenant_id,id"))
                    .as("重复执行 " + table).isEqualTo(firstRun.get(table));
        }
    }

    private static void insert(JdbcTemplate jdbc, String table, String tenant, int id, String source, String actor) {
        jdbc.update("INSERT INTO " + table + " VALUES (?,?,?,?,?,'SYSTEM','2026-09-08 06:03:46.668217',"
                + "?, '2026-09-09 21:27:21.459514', 7)", tenant, id, id, source, source, actor);
    }
}
