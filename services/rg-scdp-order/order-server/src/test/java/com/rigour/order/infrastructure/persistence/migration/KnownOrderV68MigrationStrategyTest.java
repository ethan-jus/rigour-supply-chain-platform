package com.rigour.order.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.infrastructure.config.LocalOrderMigrationRecoveryConfiguration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.util.UUID;

import javax.sql.DataSource;

class KnownOrderV68MigrationStrategyTest {
    @TempDir Path directory;
    Flyway flyway;
    JdbcTemplate jdbc;
    final KnownOrderV68MigrationStrategy strategy = new KnownOrderV68MigrationStrategy();

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        Files.writeString(
                directory.resolve("V67__fixture.sql"),
                "CREATE TABLE fixture (id INT PRIMARY KEY);");
        flyway = Flyway.configure().dataSource(ds).locations("filesystem:" + directory).load();
        flyway.migrate();
        // 仅隔离测试模拟数据库元数据；不伪造生产缺失脚本。
        jdbc.update(
"""
INSERT INTO "flyway_schema_history" ("installed_rank","version","description","type","script","checksum","installed_by","execution_time","success")
VALUES (2,'68','payment owner evidence','SQL','V68__payment_owner_evidence.sql',1727099056,'fixture',1,TRUE)
""");
        Files.writeString(
                directory.resolve("V69__fixture.sql"), "INSERT INTO fixture VALUES (69);");
    }

    @Test
    void knownMissingHistoryAllowsForwardMigrationOnceWithoutRewritingHistory() {
        var before =
                jdbc.queryForMap("SELECT * FROM \"flyway_schema_history\" WHERE \"version\"='68'");
        assertThat(strategy.hasKnownMissingHistory(flyway)).isTrue();
        strategy.migrate(flyway);
        strategy.migrate(flyway);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture WHERE id=69", Integer.class))
                .isOne();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"flyway_schema_history\" WHERE \"version\"='68'"))
                .isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"checksum", "script", "description", "type", "success"})
    void changedOrFailedKnownRecordCannotBeIgnored(String field) {
        String value =
                switch (field) {
                    case "checksum" -> "1";
                    case "success" -> "FALSE";
                    case "type" -> "'JDBC'";
                    default -> "'unrecognized'";
                };
        jdbc.update(
                "UPDATE \"flyway_schema_history\" SET \""
                        + field
                        + "\"="
                        + value
                        + " WHERE \"version\"='68'");
        blocked();
    }

    @Test
    void anotherMissingVersionBlocksStartup() throws Exception {
        Files.delete(directory.resolve("V67__fixture.sql"));
        blocked();
    }

    @Test
    void changedLocalResolvedScriptBlocksStartup() throws Exception {
        Files.writeString(
                directory.resolve("V67__fixture.sql"), "CREATE TABLE fixture (id BIGINT);");
        blocked();
    }

    @Test
    void conflictingLocalV68CannotBeIgnored() throws Exception {
        Files.writeString(directory.resolve("V68__logistics_transport_types.sql"), "SELECT 1;");
        blocked();
    }

    @Test
    void unknownFutureVersionCannotBeIgnored() {
        jdbc.update(
"""
INSERT INTO "flyway_schema_history" ("installed_rank","version","description","type","script","checksum","installed_by","execution_time","success")
VALUES (3,'70','unrecognized','SQL','V70__unrecognized.sql',1,'fixture',1,TRUE)
""");
        blocked();
    }

    @Test
    void latestKnownHistoryWithoutPendingMigrationIsStillReadOnly() throws Exception {
        Files.delete(directory.resolve("V69__fixture.sql"));
        var before = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\"");
        strategy.migrate(flyway);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\"")).isEqualTo(before);
    }

    @Test
    void noConflictUsesNormalMigration() {
        jdbc.update("DELETE FROM \"flyway_schema_history\" WHERE \"version\"='68'");
        assertThat(strategy.hasKnownMissingHistory(flyway)).isFalse();
        strategy.migrate(flyway);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class)).isOne();
    }

    @Test
    void bootInitializerUsesTheOptedInStrategy() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withUserConfiguration(LocalOrderMigrationRecoveryConfiguration.class)
                .withBean(DataSource.class, () -> flyway.getConfiguration().getDataSource())
                .withPropertyValues(
                        "spring.profiles.active=dev,local",
                        "rigour.order.flyway.allow-missing-v68=true",
                        "spring.flyway.locations=filesystem:" + directory)
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class)).isOne();
    }

    void blocked() {
        var before =
                jdbc.queryForList(
                        "SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");
        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class)).isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM \"flyway_schema_history\" ORDER BY"
                                        + " \"installed_rank\""))
                .isEqualTo(before);
    }
}
