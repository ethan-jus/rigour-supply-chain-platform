package com.rigour.integration.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.infrastructure.config.IntegrationMigrationResourcesConfiguration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
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
import java.util.*;

import javax.sql.DataSource;

class IntegrationV34MigrationResourcesTest {
    @TempDir Path directory;
    DataSource dataSource;
    JdbcTemplate jdbc;
    static final String ALTERNATE = "V34__retire_feishu_import_configuration.sql";
    static final String CANONICAL = "V34__dhb_carrier_backfill_progress.sql";

    @BeforeEach
    void setup() throws Exception {
        dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        Files.writeString(
                directory.resolve("V33__fixture.sql"),
                "CREATE TABLE fixture(id INT PRIMARY KEY);");
        configuration().load().migrate();
        for (String name : List.of(ALTERNATE, CANONICAL))
            Files.copy(Path.of("src/main/resources/db/migration", name), directory.resolve(name));
        Files.writeString(
                directory.resolve("V35__fixture.sql"), "INSERT INTO fixture VALUES (35);");
    }

    FluentConfiguration configuration() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + directory)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .outOfOrder(false)
                .validateOnMigrate(true);
    }

    void history(String script) {
        boolean alternate = ALTERNATE.equals(script);
        jdbc.update(
                """
INSERT INTO "flyway_schema_history"
("installed_rank","version","description","type","script","checksum",
 "installed_by","execution_time","success") VALUES (2,'34',?,'SQL',?,?,'fixture',1,TRUE)
""",
                alternate ? "retire feishu import configuration" : "dhb carrier backfill progress",
                script,
                alternate ? 1051428510 : 864899347);
    }

    @ParameterizedTest
    @ValueSource(strings = {ALTERNATE, CANONICAL})
    void validatesBothRealHistoriesAndOnlyAppliesForwardMigration(String script) {
        history(script);
        var before =
                jdbc.queryForMap("SELECT * FROM \"flyway_schema_history\" WHERE \"version\"='34'");
        var configuration = configuration();
        IntegrationV34MigrationResources.configure(configuration);
        var flyway = configuration.load();
        flyway.migrate();
        flyway.migrate();
        flyway.validate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture WHERE id=35", Integer.class))
                .isOne();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"flyway_schema_history\" WHERE \"version\"='34'"))
                .isEqualTo(before);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("35");
    }

    @Test
    void newDatabaseChoosesCanonicalHistoryWithoutCreatingAnyTable() {
        var fresh =
                Flyway.configure()
                        .dataSource(
                                new DriverManagerDataSource(
                                        "jdbc:h2:mem:" + UUID.randomUUID(), "sa", ""))
                        .locations("filesystem:" + directory);
        IntegrationV34MigrationResources.configure(fresh);
        assertThat(fresh.getResourceProvider().getResources("V34", new String[] {".sql"}))
                .extracting(r -> r.getFilename())
                .containsExactly(CANONICAL);
    }

    @Test
    void classpathStartupUsesTheSameSelectionAndDoesNotApplyUnselectedSql() {
        history(ALTERNATE);
        var configuration =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration", "classpath:db/bootstrap");
        IntegrationV34MigrationResources.configure(configuration);
        assertThat(configuration.getResourceProvider().getResources("V34", new String[] {".sql"}))
                .extracting(r -> r.getFilename())
                .containsExactly(ALTERNATE);
        assertThat(configuration.getResourceProvider().getResources("B", new String[] {".sql"}))
                .extracting(r -> r.getFilename())
                .contains("B27__general_ci_bootstrap.sql");
    }

    @Test
    void springBootCustomizerWorksWithTheNormalFlywayInitializer() {
        history(ALTERNATE);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withUserConfiguration(IntegrationMigrationResourcesConfiguration.class)
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(
                        "spring.flyway.locations=filesystem:" + directory,
                        "spring.flyway.validate-on-migrate=true",
                        "spring.flyway.out-of-order=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(Flyway.class)
                                                    .getConfiguration()
                                                    .getResourceProvider())
                                    .isInstanceOf(IntegrationV34MigrationResources.class);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM fixture", Integer.class))
                                    .isOne();
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"checksum", "script", "description", "type", "success"})
    void rejectsUnrecognizedOrFailedHistory(String field) {
        history(ALTERNATE);
        String value =
                switch (field) {
                    case "checksum" -> "1";
                    case "success" -> "FALSE";
                    default -> "'unrecognized'";
                };
        jdbc.update(
                "UPDATE \"flyway_schema_history\" SET \""
                        + field
                        + "\"="
                        + value
                        + " WHERE \"version\"='34'");
        assertThatThrownBy(() -> IntegrationV34MigrationResources.configure(configuration()))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("历史不是已核实");
        assertNoForwardChanges();
    }

    @Test
    void rejectsMultipleHistoryRowsForTheSameVersion() {
        history(ALTERNATE);
        jdbc.update(
                """
                INSERT INTO "flyway_schema_history"
                ("installed_rank","version","description","type","script","checksum",
                 "installed_by","execution_time","success")
                SELECT 3,"version","description","type","script","checksum","installed_by",1,TRUE
                FROM "flyway_schema_history" WHERE "version"='34'
                """);
        assertThatThrownBy(() -> IntegrationV34MigrationResources.configure(configuration()))
                .hasMessageContaining("历史不是已核实");
        assertNoForwardChanges();
    }

    @Test
    void refusesAnyChangeToEitherOriginalSql() throws Exception {
        history(ALTERNATE);
        Files.writeString(directory.resolve(CANONICAL), "SELECT 1;");
        assertThatThrownBy(() -> IntegrationV34MigrationResources.configure(configuration()))
                .hasMessageContaining("已冻结");
        assertNoForwardChanges();
    }

    @Test
    void refusesMissingOriginalResource() throws Exception {
        history(ALTERNATE);
        Files.delete(directory.resolve(CANONICAL));
        assertThatThrownBy(() -> IntegrationV34MigrationResources.configure(configuration()))
                .hasMessageContaining("原版 V34 文件各一份");
        assertNoForwardChanges();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void neverIgnoresMissingOrChangedOtherAppliedMigrations(boolean missing) throws Exception {
        history(ALTERNATE);
        if (missing) Files.delete(directory.resolve("V33__fixture.sql"));
        else
            Files.writeString(
                    directory.resolve("V33__fixture.sql"), "CREATE TABLE other_fixture(id INT);");
        var configuration = configuration();
        IntegrationV34MigrationResources.configure(configuration);
        assertThatThrownBy(() -> configuration.load().migrate())
                .isInstanceOf(FlywayException.class);
        assertNoForwardChanges();
    }

    @Test
    void neverIgnoresUnknownFutureHistory() {
        history(ALTERNATE);
        jdbc.update(
                """
                INSERT INTO "flyway_schema_history"
                ("installed_rank","version","description","type","script","checksum",
                 "installed_by","execution_time","success")
                VALUES(3,'37','unknown','SQL','V37__unknown.sql',1,'fixture',1,TRUE)
                """);
        var configuration = configuration();
        IntegrationV34MigrationResources.configure(configuration);
        assertThatThrownBy(() -> configuration.load().migrate())
                .isInstanceOf(FlywayException.class);
        assertNoForwardChanges();
    }

    void assertNoForwardChanges() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class)).isZero();
    }
}
