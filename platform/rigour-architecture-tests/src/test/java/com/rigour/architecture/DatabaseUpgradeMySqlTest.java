package com.rigour.architecture;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The new B files must never replace an existing domain's original V history or business data. */
@Testcontainers
class DatabaseUpgradeMySqlTest {
    private static final Path ROOT = Path.of(System.getProperty("repositoryRoot"));
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("upgrade_fixture").withUsername("root").withPassword("upgrade-fixture-password");

    record Upgrade(String domain, String previous) {}

    static Stream<Upgrade> upgrades() {
        return Stream.of(new Upgrade("erp", "36"),
                new Upgrade("foundation", "34"),
                new Upgrade("hr", "7"),
                new Upgrade("integration", "24"),
                new Upgrade("order", "45"),
                new Upgrade("sales", "27"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("upgrades")
    void existingVersionHistoryIsPreservedWhenNewFreshBaselinesAreAdded(Upgrade upgrade) throws Exception {
        String schema = "legacy_" + upgrade.domain();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            // Reproduce the historical MySQL default; do not silently convert old business tables.
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/upgrade_fixture", "/" + schema);
        Path migration = ROOT.resolve("services/rg-scdp-" + upgrade.domain() + "/" + upgrade.domain()
                + "-server/src/main/resources/db/migration");
        List<MigrationVersion> versions;
        try (var files = Files.list(migration)) {
            versions = files.filter(file -> file.getFileName().toString().matches("V.*__.*\\.sql"))
                    .map(file -> MigrationVersion.fromVersion(file.getFileName().toString().substring(1).split("__", 2)[0]))
                    .toList();
        }
        MigrationVersion previousVersion = MigrationVersion.fromVersion(upgrade.previous());
        assertTrue(versions.contains(previousVersion));
        MigrationVersion latest = versions.stream().max(MigrationVersion::compareTo).orElseThrow();
        int laterMigrations = (int) versions.stream().filter(version -> version.compareTo(previousVersion) > 0).count();
        Flyway previous = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + migration).target(upgrade.previous())
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load();
        previous.migrate();
        previous.validate();
        assertEquals(previousVersion, previous.info().current().getVersion());
        List<List<String>> original = history(url);
        try (var connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE upgrade_business_sentinel(id INT PRIMARY KEY, value_text VARCHAR(100))");
            statement.execute("INSERT INTO upgrade_business_sentinel VALUES(42,'existing-business-data')");
        }
        Flyway next = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + migration, "filesystem:" + migration.resolveSibling("bootstrap"))
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load();
        assertEquals(laterMigrations, next.migrate().migrationsExecuted);
        next.validate();
        assertEquals(latest, next.info().current().getVersion());
        List<List<String>> after = history(url);
        assertEquals(original, after.subList(0, original.size()));
        assertEquals(original.size() + laterMigrations, after.size());
        assertTrue(after.stream().noneMatch(row -> "SQL_BASELINE".equals(row.get(3))));
        try (var connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,value_text FROM upgrade_business_sentinel")) {
            assertTrue(rows.next());
            assertEquals(42, rows.getInt(1));
            assertEquals("existing-business-data", rows.getString(2));
            assertFalse(rows.next());
        }
        assertEquals(0, next.migrate().migrationsExecuted);
        next.validate();
        assertEquals(after, history(url));
    }

    private List<List<String>> history(String url) throws Exception {
        List<List<String>> result = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT installed_rank,version,description,type,script,checksum,"
                     + "installed_by,execution_time,success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= 9; column++) row.add(rows.getString(column));
                result.add(row);
            }
        }
        return result;
    }
}
