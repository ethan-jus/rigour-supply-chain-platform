package com.rigour.settings.infrastructure.persistence.migration;

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
class FoundationV61ConvergenceMySqlTest {
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
                migration(ds, cohort.equals("canonical") ? "61" : "60").migrate();
                if (cohort.equals("alternate")) {
                    try (var c = ds.getConnection()) {
                        ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V61__erp_product_specification_status_dictionaries.sql"));
                    }
                    jdbc.update("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
                        + "SELECT MAX(installed_rank)+1,'61','erp product specification status dictionaries','SQL','V61__erp_product_specification_status_dictionaries.sql',1720242270,'fixture',1,TRUE FROM flyway_schema_history");
                }
            }
            var history = cohort.equals("fresh") ? List.of() : jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            jdbc.execute("CREATE TABLE migration_sentinel(id INT PRIMARY KEY)");
            jdbc.update("INSERT INTO migration_sentinel VALUES(42)");
            // A fresh schema must have no tables when Flyway decides whether to apply B.
            if (cohort.equals("fresh")) {
                jdbc.execute("DROP TABLE migration_sentinel");
                migration(ds, "61").migrate();
                jdbc.execute("CREATE TABLE migration_sentinel(id INT PRIMARY KEY)");
                jdbc.update("INSERT INTO migration_sentinel VALUES(42)");
            }
            jdbc.update("INSERT INTO data_dictionary(tenant_key,dictionary_code,dictionary_name,dictionary_type,created_by) VALUES('migration-test','PRODUCT_SPECIFICATION_STATUS','自定义规格状态','ERP','TEST')");
            jdbc.update("INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,created_by) VALUES('migration-test','PRODUCT_SPECIFICATION_STATUS','ACTIVE','租户自定义启用','TEST')");
            var flyway = migration(ds, "latest");
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("62");
            assertThat(jdbc.queryForObject("SELECT id FROM migration_sentinel", Integer.class)).isEqualTo(42);
            if (!history.isEmpty()) assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank").subList(0, history.size())).isEqualTo(history);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code IN ('PRODUCT_SPECIFICATION_STATUS','PRODUCT_SPECIFICATION_VALUE_STATUS') AND dictionary_item_code IN ('ACTIVE','INACTIVE','ARCHIVED')", Integer.class)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT dictionary_name FROM data_dictionary WHERE tenant_key='GLOBAL' AND dictionary_code='COST_DELIVERY_MODE'", String.class)).isEqualTo("配送方式");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_dictionary WHERE dictionary_code='COST_TRANSPORT_METHOD' AND deleted=0", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT dictionary_item_name FROM data_dictionary_item WHERE tenant_key='migration-test' AND dictionary_code='PRODUCT_SPECIFICATION_STATUS'", String.class)).isEqualTo("租户自定义启用");
            var beforeReplay = state(jdbc);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V62__converge_logistics_labels_and_product_specification_status.sql"));
            }
            assertThat(state(jdbc)).isEqualTo(beforeReplay);
        }
    }

    private List<Object> state(JdbcTemplate jdbc) {
        List<Object> result = new ArrayList<>();
        for (String table : List.of("data_dictionary", "data_dictionary_item")) result.add(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        return result;
    }

    private Flyway migration(DriverManagerDataSource ds, String target) {
        var config = Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/bootstrap")
                .target(target).baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true);
        FoundationV61MigrationResources.configure(config);
        return config.load();
    }
}
