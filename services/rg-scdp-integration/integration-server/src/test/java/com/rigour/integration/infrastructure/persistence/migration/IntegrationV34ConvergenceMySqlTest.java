package com.rigour.integration.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 原版脚本在一次性 MySQL 中执行，三种路径汇合；不连接 DEV 或生产。 */
@Testcontainers(disabledWithoutDocker = true)
class IntegrationV34ConvergenceMySqlTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("migration_fixture").withUsername("root").withPassword("fixture-password");

    @Test
    void bothHistoriesAndFreshInstallConvergeWithoutRewritingHistoryOrBusinessData() throws Exception {
        for (String cohort : List.of("fresh", "canonical", "alternate")) {
            String schema = "migration_" + cohort;
            try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                 var st = c.createStatement()) {
                st.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            }
            var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl().replace("/migration_fixture", "/" + schema),
                    MYSQL.getUsername(), MYSQL.getPassword());
            var jdbc = new JdbcTemplate(ds);
            if (!cohort.equals("fresh")) {
                migration(ds, cohort.equals("canonical") ? "34" : "33").migrate();
                if (cohort.equals("alternate")) {
                    try (var c = ds.getConnection()) {
                        ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V34__retire_feishu_import_configuration.sql"));
                    }
                    jdbc.update("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
                        + "SELECT MAX(installed_rank)+1,'34','retire feishu import configuration','SQL','V34__retire_feishu_import_configuration.sql',1051428510,'fixture',1,TRUE FROM flyway_schema_history");
                }
            }
            var history = cohort.equals("fresh") ? List.of() : jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            jdbc.execute("CREATE TABLE migration_sentinel(id INT PRIMARY KEY)");
            jdbc.update("INSERT INTO migration_sentinel VALUES(42)");
            // A fresh schema must have no tables when Flyway decides whether to apply B.
            if (cohort.equals("fresh")) {
                jdbc.execute("DROP TABLE migration_sentinel");
                migration(ds, "34").migrate();
                jdbc.execute("CREATE TABLE migration_sentinel(id INT PRIMARY KEY)");
                jdbc.update("INSERT INTO migration_sentinel VALUES(42)");
            }

            jdbc.update("INSERT INTO integration_dictionary_mapping(tenant_id,source_system,source_scope,dictionary_code,source_field,source_value,source_value_hash,created_by,updated_by) "
                    + "VALUES('00000000-0000-0000-0000-000000000001','DHB','shipment','COST_CARRIER','carrier','fixture',UNHEX(SHA2('fixture',256)),'TEST','TEST')");
            if (!cohort.equals("alternate")) jdbc.update("UPDATE integration_dictionary_mapping SET carrier_backfill_revision=7");
            jdbc.update("INSERT INTO integration_feishu_import_batch(id,tenant_id,original_file_name,file_size_bytes,file_sha256,status,created_at,updated_at) "
                    + "VALUES(UUID_TO_BIN(UUID()),UUID_TO_BIN('00000000-0000-0000-0000-000000000001'),'history.xlsx',42,REPEAT('0',64),'SUCCEEDED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            var batches = jdbc.queryForList("SELECT * FROM integration_feishu_import_batch");
            var flyway = migration(ds, "latest");
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("36");
            assertThat(jdbc.queryForObject("SELECT id FROM migration_sentinel", Integer.class)).isEqualTo(42);
            if (!history.isEmpty()) assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank").subList(0, history.size())).isEqualTo(history);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='integration_dictionary_mapping' AND column_name='carrier_backfill_revision'", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_feishu_import_template WHERE source_system='FEISHU'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_feishu_import_template_dependency WHERE source_system='FEISHU'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT carrier_backfill_revision FROM integration_dictionary_mapping", Integer.class))
                    .isEqualTo(cohort.equals("alternate") ? 0 : 7);
            assertThat(jdbc.queryForList("SELECT * FROM integration_feishu_import_batch")).usingRecursiveComparison().isEqualTo(batches);
            var beforeReplay = state(jdbc);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V35__converge_carrier_progress_and_retired_import_configuration.sql"));
            }
            assertThat(state(jdbc)).usingRecursiveComparison().isEqualTo(beforeReplay);
        }
    }

    private List<Object> state(JdbcTemplate jdbc) {
        List<Object> result = new ArrayList<>();
        for (String table : List.of("integration_dictionary_mapping", "integration_feishu_import_template", "integration_feishu_import_template_dependency")) result.add(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        return result;
    }

    private Flyway migration(DriverManagerDataSource ds, String target) {
        var config = Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/bootstrap")
                .target(target).baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true);
        IntegrationV34MigrationResources.configure(config);
        return config.load();
    }
}
