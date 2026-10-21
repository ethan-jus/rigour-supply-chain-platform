package com.rigour.architecture;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseCollationPolicyTest {
    private static final Path ROOT = Path.of(System.getProperty("repositoryRoot"));
    private static final Pattern COLLATION = Pattern.compile("\\butf8mb4_[a-z0-9_]+\\b", Pattern.CASE_INSENSITIVE);
    @TempDir Path temporary;

    @Test
    void frozenHistoryAndBaselinesRemainUnchangedAndNewSqlUsesGeneralCi() throws Exception {
        List<Map<String, Object>> entries = new Yaml().load(Files.readString(
                ROOT.resolve("scripts/database/general-ci-baselines.json")));
        assertEquals(10, entries.size());
        assertEquals(9, entries.stream().map(entry -> entry.get("migration_directory")).distinct().count());
        for (var entry : entries) {
            Path directory = ROOT.resolve((String) entry.get("migration_directory"));
            MigrationVersion ceiling = MigrationVersion.fromVersion((String) entry.get("through_version"));
            Map<?, ?> legacy = (Map<?, ?>) entry.getOrDefault("legacy_migrations", Map.of());
            List<String> verifiedLegacy = new ArrayList<>();
            List<Path> historical = new ArrayList<>();
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().matches("V.*__.*\\.sql")).toList()) {
                    if (version(file).compareTo(ceiling) <= 0) historical.add(file);
                    else if (legacy.containsKey(file.getFileName().toString())) {
                        assertEquals(legacy.get(file.getFileName().toString()), HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))), file.toString());
                        verifiedLegacy.add(file.getFileName().toString());
                    } else assertGeneralCi(file);
                }
            }
            assertEquals(legacy.keySet(), new java.util.HashSet<>(verifiedLegacy), directory.toString());
            historical.sort(Comparator.comparing(DatabaseCollationPolicyTest::version));
            assertEquals(((Number) entry.get("migration_count")).intValue(), historical.size(), directory.toString());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : historical) {
                digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
            assertEquals(entry.get("source_sha256"), HexFormat.of().formatHex(digest.digest()), directory.toString());
            Path baseline = ROOT.resolve((String) entry.get("baseline"));
            assertEquals(entry.get("baseline_sha256"), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(baseline))), baseline.toString());
            assertGeneralCi(baseline);
        }
    }

    @Test
    void runtimeSelectsBothLocationsAndProductionMigrationStaysDisabled() throws Exception {
        List<Map<String, Object>> entries = new Yaml().load(Files.readString(
                ROOT.resolve("scripts/database/general-ci-baselines.json")));
        for (var entry : entries) {
            Path resources = ROOT.resolve((String) entry.get("migration_directory")).getParent().getParent();
            for (String name : List.of("application.yml", "application-dev.yml")) {
                Map<?, ?> config = new Yaml().load(Files.readString(resources.resolve(name)));
                Map<?, ?> flyway = (Map<?, ?>) ((Map<?, ?>) config.get("spring")).get("flyway");
                assertEquals("classpath:db/migration,classpath:db/bootstrap", flyway.get("locations"), resources.toString());
            }
            Map<?, ?> prod = new Yaml().load(Files.readString(resources.resolve("application-prod.yml")));
            Map<?, ?> flyway = (Map<?, ?>) ((Map<?, ?>) prod.get("spring")).get("flyway");
            assertEquals(false, flyway.get("enabled"), resources.toString());
        }
    }

    @Test
    void emptyDatabaseSelectsBaselineButExistingHistoryContinuesVersionedMigrations() throws Exception {
        Path versions = Files.createDirectory(temporary.resolve("migration"));
        Path bootstraps = Files.createDirectory(temporary.resolve("bootstrap"));
        Files.writeString(versions.resolve("V1__old.sql"),
                "CREATE TABLE sentinel(id INT PRIMARY KEY, source VARCHAR(20)); INSERT INTO sentinel VALUES(1,'historical');");
        Files.writeString(versions.resolve("V2__later.sql"), "INSERT INTO sentinel VALUES(2,'later');");
        Files.writeString(bootstraps.resolve("B2__bootstrap.sql"),
                "CREATE TABLE sentinel(id INT PRIMARY KEY, source VARCHAR(20)); INSERT INTO sentinel VALUES(1,'bootstrap'); INSERT INTO sentinel VALUES(2,'later');");
        Files.writeString(versions.resolve("V3__future.sql"), "INSERT INTO sentinel VALUES(3,'future');");

        String existing = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(existing, "sa", "").locations("filesystem:" + versions).target("1").load().migrate();
        Flyway existingUpgrade = flyway(existing, versions, bootstraps);
        assertEquals(2, existingUpgrade.migrate().migrationsExecuted);
        existingUpgrade.validate();
        assertSource(existing, "historical");
        assertEquals(0, existingUpgrade.migrate().migrationsExecuted);

        String fresh = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway freshInit = flyway(fresh, versions, bootstraps);
        assertEquals(2, freshInit.migrate().migrationsExecuted); // B2 + V3, not V1/V2.
        freshInit.validate();
        assertSource(fresh, "bootstrap");
        assertEquals(0, freshInit.migrate().migrationsExecuted);
    }

    private Flyway flyway(String url, Path versions, Path bootstraps) {
        return Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + versions, "filesystem:" + bootstraps)
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).outOfOrder(false).load();
    }

    private void assertSource(String url, String expected) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT source, (SELECT COUNT(*) FROM sentinel) FROM sentinel WHERE id=1")) {
            assertTrue(result.next());
            assertEquals(expected, result.getString(1));
            assertEquals(3, result.getInt(2));
        }
    }

    private static MigrationVersion version(Path path) {
        return MigrationVersion.fromVersion(path.getFileName().toString().substring(1).split("__", 2)[0]);
    }

    private static void assertGeneralCi(Path path) throws Exception {
        var matcher = COLLATION.matcher(Files.readString(path));
        while (matcher.find()) assertEquals("utf8mb4_general_ci", matcher.group().toLowerCase(), path.toString());
    }
}
