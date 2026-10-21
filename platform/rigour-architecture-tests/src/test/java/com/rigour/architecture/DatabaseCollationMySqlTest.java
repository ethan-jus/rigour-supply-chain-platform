package com.rigour.architecture;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs every actual service baseline only in a disposable MySQL container, never against DEV/prod. */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseCollationMySqlTest {
    private static final Path ROOT = Path.of(System.getProperty("repositoryRoot"));
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static Stream<String> directories() throws Exception {
        List<Map<String, Object>> entries = new Yaml().load(Files.readString(
                ROOT.resolve("scripts/database/general-ci-baselines.json")));
        return entries.stream().map(entry -> (String) entry.get("migration_directory")).distinct();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("directories")
    void freshSchemaHasOnlyGeneralCiAndCanBeValidatedAndRestarted(String directory) throws Exception {
        Path migrations = ROOT.resolve(directory);
        MigrationVersion baselineVersion;
        try (var files = Files.list(migrations.resolveSibling("bootstrap"))) {
            baselineVersion = files.filter(p -> p.getFileName().toString().matches("B.*__.*\\.sql"))
                    .map(p -> MigrationVersion.fromVersion(p.getFileName().toString().substring(1).split("__")[0]))
                    .max(MigrationVersion::compareTo).orElseThrow();
        }
        Flyway flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + migrations, "filesystem:" + migrations.resolveSibling("bootstrap"))
                .target(baselineVersion)
                .schemas(MYSQL.getDatabaseName()).baselineOnMigrate(false)
                .cleanDisabled(false) // This URL is owned exclusively by the disposable container above.
                .load();
        flyway.clean();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            for (String sql : List.of(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=DATABASE() AND default_collation_name <> 'utf8mb4_general_ci'",
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_collation <> 'utf8mb4_general_ci'",
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND collation_name IS NOT NULL AND collation_name <> 'utf8mb4_general_ci'")) {
                try (var result = statement.executeQuery(sql)) {
                    assertTrue(result.next());
                    assertEquals(0, result.getLong(1), directory + ": " + sql);
                }
            }
        }
    }
}
