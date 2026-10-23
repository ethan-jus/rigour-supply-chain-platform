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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.sql.SQLException;
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
                // This case validates the frozen B snapshot itself, not the later V upgrade chain.
                // IAM historical V139 selection is verified by its domain migration tests.
                .locations("filesystem:" + migrations.resolveSibling("bootstrap"))
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
            if (directory.contains("rg-scdp-integration/")) verifyIntegrationChecksum(connection);
        }
    }

    private void verifyIntegrationChecksum(java.sql.Connection connection) throws Exception {
        String payload = "{\"tables\":[],\"name\":\"摘要内容回归\"}";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        assertTrue(digest.matches("[0-9a-f]{64}"));
        String tenant = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString();
        String first = UUID.randomUUID().toString();
        String sql = "INSERT INTO integration_feishu_online_capture(id,tenant_id,actor_id,source_id,source_name,"
                + "source_url,started_at,completed_at,complete,filtered,checksum,record_count,page_count,payload_json)"
                + " VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'fixture','fixture','https://example.invalid',"
                + "'2026-10-23 01:00:00','2026-10-23 01:00:01',TRUE,FALSE,?,0,1,?)";
        try (var insert = connection.prepareStatement(sql)) {
            for (String id : List.of(first, UUID.randomUUID().toString())) {
                insert.setString(1, id); insert.setString(2, tenant); insert.setString(3, actor);
                insert.setString(4, digest); insert.setString(5, payload);
                assertEquals(1, insert.executeUpdate());
            }
            // Capture identity is unique; identical payload digests on distinct captures remain legal.
            insert.setString(1, first);
            SQLException conflict = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, insert::executeUpdate);
            assertEquals("23000", conflict.getSQLState());
            assertEquals(1062, conflict.getErrorCode());
        }
        try (var query = connection.prepareStatement("SELECT checksum,payload_json,LOWER(SHA2(payload_json,256))"
                + " FROM integration_feishu_online_capture WHERE tenant_id=UUID_TO_BIN(?)")) {
            query.setString(1, tenant);
            try (var rows = query.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    assertEquals(digest, rows.getString(1));
                    assertEquals(payload, rows.getString(2));
                    assertEquals(digest, rows.getString(3));
                    count++;
                }
                assertEquals(2, count);
            }
        }
    }
}
