import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import org.flywaydb.core.Flyway;

/**
 * One-time empty-schema initialization using the deployed Integration JAR's migrations and libraries.
 * Run on the database host with JDK 21. The JDBC target is deliberately fixed to local MySQL:3306.
 * Usage: java --class-path 'extracted/BOOT-INF/lib/*' InitializeIntegrationDatabase.java
 *        expected-mysql-hostname extracted/BOOT-INF/classes/db/migration
 * Stop Integration before running. Never reads Nacos or development defaults; refuses every nonempty
 * schema, including partial runs. Runs the B24 general-ci baseline (V1-V24); it is not an upgrade
 * tool. Retains built-in Feishu template configuration and Flyway history; imports no business data.
 */
public final class InitializeIntegrationDatabase {
    private static final String SCHEMA = "rigour_integration";
    private static final String SERVER_URL = "jdbc:mysql://127.0.0.1:3306/";
    private static final String PARAMETERS = "?useUnicode=true&characterEncoding=UTF-8"
            + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
            + "&sslMode=PREFERRED&allowPublicKeyRetrieval=true&connectTimeout=5000";

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || args[0].isBlank()) {
            throw new IllegalArgumentException("Expected MySQL hostname and extracted migration directory");
        }
        Path migrations = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(migrations.resolve("V1__integration_dinghuobao.sql"))
                || !Files.isRegularFile(migrations.resolve("V24__dhb_object_sync_checkpoint.sql"))) {
            throw new IllegalArgumentException("Integration V1-V24 migrations required: " + migrations);
        }
        Path bootstrap = migrations.resolveSibling("bootstrap");
        if (!Files.isRegularFile(bootstrap.resolve("B24__general_ci_bootstrap.sql"))) {
            throw new IllegalArgumentException("Updated Integration JAR and db/bootstrap/B24__general_ci_bootstrap.sql required");
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Run interactively in the Linux terminal; password input requires a console");
        }
        String user = console.readLine("MySQL migration username [root]: ");
        if (user == null) throw new IllegalStateException("Cancelled");
        user = user.isBlank() ? "root" : user.strip();
        char[] enteredPassword = console.readPassword("MySQL password (hidden): ");
        if (enteredPassword == null) throw new IllegalStateException("Cancelled");
        String password = new String(enteredPassword);
        Arrays.fill(enteredPassword, '\0');

        // Verify the server and empty schema before issuing any DDL.
        try (var connection = DriverManager.getConnection(SERVER_URL + PARAMETERS, user, password);
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT @@hostname, @@port")) {
                if (!result.next() || !args[0].equals(result.getString(1)) || result.getInt(2) != 3306) {
                    throw new IllegalStateException("Unexpected MySQL server; no changes applied");
                }
                System.out.println("Verified target: " + result.getString(1) + ":3306/" + SCHEMA);
            }
            verifyDatabaseCompatibility(connection);
            try (var result = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema='rigour_integration'
                    """)) {
                result.next();
                if (result.getInt(1) != 0) {
                    throw new IllegalStateException("rigour_integration is not empty; stop and inspect existing tables/history");
                }
            }
            statement.executeUpdate("""
                    CREATE DATABASE IF NOT EXISTS rigour_integration
                    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci
                    """);
            statement.executeUpdate("ALTER DATABASE rigour_integration CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(SERVER_URL + SCHEMA + PARAMETERS, user, password)
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .createSchemas(false)
                .locations("filesystem:" + migrations, "filesystem:" + bootstrap)
                .target("24")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
        var result = flyway.migrate();
        flyway.validate();
        verifyCollations(user, password);
        verifyNoBusinessData(user, password);
        System.out.printf("Integration migration OK: applied=%d, version=%s%n",
                result.migrationsExecuted, result.targetSchemaVersion);
        System.out.println("No business data imported; built-in Feishu templates and Flyway history retained.");
        System.out.println("No Integration application or background sync worker was started.");
    }

    static void verifyDatabaseCompatibility(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            String version;
            try (var result = statement.executeQuery("SELECT VERSION(), @@version_comment")) {
                if (!result.next()) throw new IllegalStateException("Cannot determine database server version");
                version = result.getString(1) + " (" + result.getString(2) + ")";
                System.out.println("Database server: " + version);
            }
            try (var result = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.COLLATIONS
                    WHERE COLLATION_NAME='utf8mb4_general_ci'
                    """)) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("Incompatible database: " + version
                            + "; Integration initialization requires utf8mb4_general_ci."
                            + " No database/table changes applied; verify the server version before continuing.");
                }
            }
            try (var result = statement.executeQuery(
                    "SELECT OCTET_LENGTH(UUID_TO_BIN('00000000-0000-0000-0000-000000000001'))")) {
                if (!result.next() || result.getInt(1) != 16) {
                    throw new IllegalStateException("Database UUID_TO_BIN capability check failed; no changes applied");
                }
            } catch (SQLException unsupported) {
                throw new IllegalStateException("Incompatible database: " + version
                        + "; Integration migrations require UUID_TO_BIN. No database/table changes applied.", unsupported);
            }
        }
    }

    private static void verifyCollations(String user, String password) throws SQLException {
        try (var connection = DriverManager.getConnection(SERVER_URL + SCHEMA + PARAMETERS, user, password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT
                       (SELECT COUNT(*) FROM information_schema.schemata
                        WHERE schema_name='rigour_integration'
                          AND default_collation_name <> 'utf8mb4_general_ci') +
                       (SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema='rigour_integration' AND table_type='BASE TABLE'
                          AND table_collation <> 'utf8mb4_general_ci') +
                       (SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema='rigour_integration' AND collation_name IS NOT NULL
                          AND collation_name <> 'utf8mb4_general_ci')
                     """)) {
            if (!result.next() || result.getLong(1) != 0) {
                throw new IllegalStateException("Database/table/text-column collation mismatch; inspect before starting Integration");
            }
        }
        System.out.println("Verified: database, tables and text columns use utf8mb4_general_ci.");
    }

    private static void verifyNoBusinessData(String user, String password) throws Exception {
        try (var connection = DriverManager.getConnection(SERVER_URL + SCHEMA + PARAMETERS, user, password)) {
            try (var statement = connection.createStatement()) {
                var tables = new ArrayList<String>();
                try (var result = statement.executeQuery("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema='rigour_integration' AND table_type='BASE TABLE'
                          AND table_name NOT IN ('flyway_schema_history', 'integration_feishu_import_template',
                                                 'integration_feishu_import_template_dependency')
                        """)) {
                    while (result.next()) tables.add(result.getString(1));
                }
                for (String table : tables) {
                    try (var result = statement.executeQuery("SELECT COUNT(*) FROM `" + table.replace("`", "``") + "`")) {
                        result.next();
                        if (result.getLong(1) != 0) {
                            throw new IllegalStateException("Unexpected business data in " + table + "; inspect before starting Integration");
                        }
                    }
                }
                try (var result = statement.executeQuery("""
                        SELECT
                          (SELECT COUNT(*) FROM integration_feishu_import_template) AS templates,
                          (SELECT COUNT(*) FROM integration_feishu_import_template_dependency) AS dependencies
                        """)) {
                    result.next();
                    if (result.getInt(1) != 6 || result.getInt(2) != 2) {
                        throw new IllegalStateException("Unexpected built-in template counts; inspect before starting Integration");
                    }
                    System.out.println("Verified: 6 built-in Feishu templates and 2 template dependencies.");
                }
            }
        }
    }
}
