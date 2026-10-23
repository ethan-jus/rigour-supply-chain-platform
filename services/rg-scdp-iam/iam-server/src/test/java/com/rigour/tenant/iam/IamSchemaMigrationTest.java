package com.rigour.tenant.iam;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import com.rigour.tenant.iam.infrastructure.persistence.migration.IamV139MigrationResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class IamSchemaMigrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private Flyway migration(boolean includeBaselines, String target) {
        String[] locations = includeBaselines
                ? new String[] {"classpath:db/migration", "classpath:db/bootstrap"}
                : new String[] {"classpath:db/migration"};
        var configuration = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations(locations).target(target).baselineOnMigrate(false).outOfOrder(false)
                .cleanDisabled(false); // Only the disposable container owned by this test.
        IamV139MigrationResources.configure(configuration);
        return configuration.load();
    }

    @BeforeEach
    void clearDisposableSchema() {
        migration(true, "latest").clean();
    }

    @Test
    void emptySchemaUsesNewBaselineAndCompletesAllFollowingMigrations() throws Exception {
        Flyway flyway = migration(true, "latest");
        flyway.migrate();
        flyway.validate();
        assertEquals("141", flyway.info().current().getVersion().getVersion());
        assertEquals(1, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version='120' AND type='SQL_BASELINE'"));
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT table_name,column_name,collation_name FROM information_schema.columns"
                     + " WHERE table_schema=DATABASE() AND collation_name IS NOT NULL AND collation_name <> 'utf8mb4_general_ci'")) {
            var unexpected = new java.util.ArrayList<String>();
            while (result.next()) unexpected.add(result.getString(1) + "." + result.getString(2) + ": " + result.getString(3));
            assertEquals(java.util.List.of(), unexpected);
        }
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void existingVersionedDatabaseKeepsHistoryAndDataThroughEveryFollowingMigration() throws Exception {
        migration(false, "132").migrate();
        long checksum = scalar("SELECT checksum FROM flyway_schema_history WHERE version='120'");
        long historyCount = scalar("SELECT COUNT(*) FROM flyway_schema_history");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_sentinel(id INT PRIMARY KEY)");
            statement.execute("INSERT INTO migration_sentinel VALUES(42)");
        }
        Flyway upgraded = migration(true, "latest");
        assertEquals(9, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        assertEquals(historyCount + 9, scalar("SELECT COUNT(*) FROM flyway_schema_history"));
        assertEquals("141", upgraded.info().current().getVersion().getVersion());
        assertEquals(checksum, scalar("SELECT checksum FROM flyway_schema_history WHERE version='120'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL_BASELINE'"));
        assertEquals(42, scalar("SELECT id FROM migration_sentinel"));
        assertEquals(0, upgraded.migrate().migrationsExecuted);
    }

    private long scalar(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
