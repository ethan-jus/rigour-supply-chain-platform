package com.rigour.tenant.iam.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.util.*;

/** 真实原SQL、冻结基线和V141在专用MySQL容器执行；不使用任何DEV连接。 */
@Testcontainers(disabledWithoutDocker = true)
class IamV139ConvergenceMySqlTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("iam_fixture")
                    .withUsername("root")
                    .withPassword("fixture-password");

    @Test
    void freshRiskAndCostHistoriesConvergeWithoutChangingHistoryOrRoleGrants() throws Exception {
        for (String cohort : List.of("fresh", "risk", "cost")) {
            String schema = "iam_" + cohort;
            try (var connection =
                            DriverManager.getConnection(
                                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE DATABASE "
                                + schema
                                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            }
            var source =
                    new DriverManagerDataSource(
                            MYSQL.getJdbcUrl().replace("/iam_fixture", "/" + schema),
                            MYSQL.getUsername(),
                            MYSQL.getPassword());
            var jdbc = new JdbcTemplate(source);
            if (!cohort.equals("fresh")) {
                migration(source, cohort.equals("risk") ? "139" : "138").migrate();
                if (cohort.equals("cost")) {
                    try (var connection = source.getConnection()) {
                        ScriptUtils.executeSqlScript(
                                connection,
                                new ClassPathResource(
                                        "db/migration/" + IamV139MigrationResourcesTest.COST));
                    }
                    jdbc.update(
                            """
INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
SELECT MAX(installed_rank)+1,'139','cost dashboard permissions','SQL',?,-812060856,'fixture',1,TRUE FROM flyway_schema_history
""",
                            IamV139MigrationResourcesTest.COST);
                }
            }
            var original =
                    cohort.equals("fresh")
                            ? null
                            : jdbc.queryForMap(
                                    "SELECT * FROM flyway_schema_history WHERE version='139'");
            var beforeRoles = cohort.equals("fresh") ? null : roles(jdbc);
            var flyway = migration(source, "latest");
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("141");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            if (original != null) {
                assertThat(
                                jdbc.queryForMap(
                                        "SELECT * FROM flyway_schema_history WHERE version='139'"))
                        .isEqualTo(original);
                assertThat(roles(jdbc)).isEqualTo(beforeRoles);
            }
            assertThat(
                            jdbc.queryForObject(
                                    """
SELECT COUNT(*) FROM iam_resource r JOIN iam_resource_ui u ON u.resource_id=r.id
WHERE u.route_key='supply.bi.city-cost' AND r.permission_code='analytics:cost-dashboard:read'
""",
                                    Integer.class))
                    .isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT permission_code FROM iam_resource WHERE"
                                        + " resource_code='SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK'",
                                    String.class))
                    .isEqualTo("analytics:dashboard:read");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM iam_resource WHERE"
                                        + " resource_code='SUPPLY_CHAIN.BI.COST_DASHBOARD.EXPORT'",
                                    Integer.class))
                    .isOne();
            var afterRoles = roles(jdbc);
            int menuCount =
                    jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_menu_node", Integer.class);
            int packageCount =
                    jdbc.queryForObject("SELECT COUNT(*) FROM iam_package_resource", Integer.class);
            // 独立执行第二次原V141，确认所有INSERT确实幂等，而不只依赖Flyway不重跑。
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(
                                "db/migration/V141__iam_converge_cost_and_payment_risk_permissions.sql"));
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_menu_node", Integer.class))
                    .isEqualTo(menuCount);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM iam_package_resource", Integer.class))
                    .isEqualTo(packageCount);
            assertThat(roles(jdbc)).isEqualTo(afterRoles);
        }
    }

    private Flyway migration(DriverManagerDataSource source, String target) {
        var configuration =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/migration", "classpath:db/bootstrap")
                        .target(target)
                        .validateOnMigrate(true)
                        .baselineOnMigrate(false)
                        .outOfOrder(false)
                        .cleanDisabled(true);
        IamV139MigrationResources.configure(configuration);
        return configuration.load();
    }

    private Map<String, List<Map<String, Object>>> roles(JdbcTemplate jdbc) {
        Map<String, List<Map<String, Object>>> snapshot = new TreeMap<>();
        for (String table :
                List.of("iam_role_resource", "iam_app_role_grant", "iam_app_member_role_scope")) {
            var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1,2");
            rows.forEach(
                    row ->
                            row.replaceAll(
                                    (key, value) ->
                                            value instanceof byte[] bytes
                                                    ? HexFormat.of().formatHex(bytes)
                                                    : value));
            snapshot.put(table, rows);
        }
        return snapshot;
    }
}
