package com.rigour.tenant.iam.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.tenant.iam.infrastructure.config.LocalIamMigrationRecoveryConfiguration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.sql.DataSource;

class KnownMissingV133MigrationStrategyTest {
    @TempDir Path directory;
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private final KnownMissingV133MigrationStrategy strategy =
            new KnownMissingV133MigrationStrategy();

    @BeforeEach
    void setUp() throws Exception {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        Files.writeString(
                directory.resolve("V132__fixture.sql"),
                "CREATE TABLE fixture (id INT PRIMARY KEY);");
        flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("filesystem:" + directory)
                        .load();
        flyway.migrate();
        // 模拟共享库中同事已经成功执行的历史条目；不制造或猜测缺失脚本的内容。
        jdbc.update(
                """
INSERT INTO "flyway_schema_history"
("installed_rank","version","description","type","script","checksum",
 "installed_by","execution_time","success") VALUES (2,'133',?,'SQL',?,?,'fixture',1,TRUE)
""",
                "iam native app session client",
                "V133__iam_native_app_session_client.sql",
                -1799016397);
        Files.writeString(
                directory.resolve("V134__fixture.sql"), "INSERT INTO fixture VALUES (134);");
    }

    @Test
    void migratesPendingVersionOnceWithoutChangingOriginalHistory() {
        var original =
                jdbc.queryForMap("SELECT * FROM \"flyway_schema_history\" WHERE \"version\"='133'");
        strategy.migrate(flyway);
        strategy.migrate(flyway);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture WHERE id=134", Integer.class))
                .isOne();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"flyway_schema_history\" WHERE \"version\"='133'"))
                .isEqualTo(original);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL",
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void refusesAnotherMissingMigration() throws Exception {
        Files.delete(directory.resolve("V132__fixture.sql"));
        assertBlocked("其他迁移问题必须修复");
    }

    @Test
    void refusesChecksumChangesInOtherMigrations() throws Exception {
        Files.writeString(
                directory.resolve("V132__fixture.sql"),
                "CREATE TABLE fixture (id BIGINT PRIMARY KEY);");
        assertBlocked("其他迁移问题必须修复");
    }

    @Test
    void refusesDifferentOriginalChecksum() {
        jdbc.update("UPDATE \"flyway_schema_history\" SET \"checksum\"=1 WHERE \"version\"='133'");
        assertBlocked("记录不一致");
    }

    @Test
    void refusesDifferentOriginalFilename() {
        jdbc.update(
                "UPDATE \"flyway_schema_history\" SET \"script\"='V133__other.sql' WHERE"
                    + " \"version\"='133'");
        assertBlocked("记录不一致");
    }

    @Test
    void refusesFailedOriginalMigration() {
        jdbc.update(
                "UPDATE \"flyway_schema_history\" SET \"success\"=FALSE WHERE \"version\"='133'");
        assertBlocked("其他迁移问题必须修复");
    }

    @Test
    void refusesPlaceholderForTheOriginalMigration() throws Exception {
        Files.writeString(
                directory.resolve("V133__iam_native_app_session_client.sql"), "SELECT 1;");
        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(FlywayException.class);
        assertNotMigrated();
    }

    @Test
    void refusesAnotherFutureMigration() {
        jdbc.update(
                """
                INSERT INTO "flyway_schema_history"
                ("installed_rank","version","description","type","script","checksum",
                 "installed_by","execution_time","success")
                VALUES (3,'135','other','SQL','V135__other.sql',1,'fixture',1,TRUE)
                """);
        assertBlocked("其他迁移问题必须修复");
    }

    @Test
    void refusesDatabaseWithoutTheOriginalMigration() {
        jdbc.update("DELETE FROM \"flyway_schema_history\" WHERE \"version\"='133'");
        assertBlocked("不能用于跳过新库迁移");
    }

    @Test
    void startsWithOnlyKnownV147MissingAndPreservesHistory() throws Exception {
        prepareV147History();
        Files.copy(
                Path.of("src/main/resources/db/migration/V133__iam_native_app_session_client.sql"),
                directory.resolve("V133__iam_native_app_session_client.sql"));
        Files.writeString(
                directory.resolve("V148__fixture.sql"), "INSERT INTO fixture VALUES (148);");
        var original =
                jdbc.queryForList("SELECT * FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withUserConfiguration(LocalIamMigrationRecoveryConfiguration.class)
                .withBean(DataSource.class, () -> flyway.getConfiguration().getDataSource())
                .withPropertyValues(
                        "spring.profiles.active=dev,local",
                        "rigour.iam.flyway.allow-missing-v133=true",
                        "spring.flyway.locations=filesystem:" + directory)
                .run(context -> assertThat(context).hasNotFailed());

        strategy.migrate(flyway);
        assertThat(jdbc.queryForList(
                        "SELECT * FROM \"flyway_schema_history\" WHERE \"installed_rank\"<=4"
                                + " ORDER BY \"installed_rank\""))
                .isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture WHERE id=148", Integer.class))
                .isOne();
    }

    @Test
    void allowsKnownV147AsTheLatestAppliedVersion() {
        prepareV147History();
        var before = jdbc.queryForList("SELECT * FROM \"flyway_schema_history\"");
        strategy.migrate(flyway);
        assertThat(jdbc.queryForList("SELECT * FROM \"flyway_schema_history\""))
                .isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"checksum", "script", "description", "type", "success"})
    void refusesUnrecognizedOrFailedV147(String field) {
        prepareV147History();
        String value =
                switch (field) {
                    case "checksum" -> "1";
                    case "success" -> "FALSE";
                    case "type" -> "'JDBC'";
                    default -> "'unrecognized'";
                };
        jdbc.update(
                "UPDATE \"flyway_schema_history\" SET \"" + field + "\"=" + value
                        + " WHERE \"version\"='147'");
        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("其他迁移问题必须修复");
    }

    @Test
    void refusesUnknownFutureVersionAlongsideKnownV147() {
        prepareV147History();
        jdbc.update(
                """
                INSERT INTO "flyway_schema_history"
                ("installed_rank","version","description","type","script","checksum",
                 "installed_by","execution_time","success")
                VALUES (5,'149','other','SQL','V149__other.sql',1,'fixture',1,TRUE)
                """);
        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("其他迁移问题必须修复");
    }

    private void prepareV147History() {
        strategy.migrate(flyway);
        jdbc.update(
                """
                INSERT INTO "flyway_schema_history"
                ("installed_rank","version","description","type","script","checksum",
                 "installed_by","execution_time","success")
                VALUES (4,'147','hr employee delete permission','SQL',
                        'V147__hr_employee_delete_permission.sql',-1379744324,'fixture',1,TRUE)
                """);
    }

    private void assertBlocked(String message) {
        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(message);
        assertNotMigrated();
    }

    private void assertNotMigrated() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE"
                                    + " \"version\"='134'",
                                Integer.class))
                .isZero();
    }
}
