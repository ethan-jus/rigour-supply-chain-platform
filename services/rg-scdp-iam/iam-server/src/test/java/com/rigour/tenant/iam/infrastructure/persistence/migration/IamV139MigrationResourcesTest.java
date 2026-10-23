package com.rigour.tenant.iam.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.tenant.iam.infrastructure.config.IamMigrationResourcesConfiguration;

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

class IamV139MigrationResourcesTest {
    @TempDir Path directory;
    DataSource dataSource;
    JdbcTemplate jdbc;
    static final String COST = "V139__cost_dashboard_permissions.sql";
    static final String RISK = "V139__iam_payment_risk_page_read_permission.sql";

    @BeforeEach
    void setup() throws Exception {
        dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        Files.writeString(
                directory.resolve("V138__fixture.sql"),
                "CREATE TABLE fixture(id INT PRIMARY KEY);");
        configuration().load().migrate();
        for (String name : List.of(COST, RISK))
            Files.copy(Path.of("src/main/resources/db/migration", name), directory.resolve(name));
        Files.writeString(
                directory.resolve("V140__fixture.sql"), "INSERT INTO fixture VALUES (140);");
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
        boolean cost = COST.equals(script);
        jdbc.update(
                """
INSERT INTO "flyway_schema_history"
("installed_rank","version","description","type","script","checksum",
 "installed_by","execution_time","success") VALUES (2,'139',?,'SQL',?,?,'fixture',1,TRUE)
""",
                cost ? "cost dashboard permissions" : "iam payment risk page read permission",
                script,
                cost ? -812060856 : 466362980);
    }

    @ParameterizedTest
    @ValueSource(strings = {COST, RISK})
    void validatesBothRealHistoriesAndOnlyAppliesForwardMigration(String script) {
        history(script);
        var before =
                jdbc.queryForMap("SELECT * FROM \"flyway_schema_history\" WHERE \"version\"='139'");
        var configuration = configuration();
        IamV139MigrationResources.configure(configuration);
        var flyway = configuration.load();
        flyway.migrate();
        flyway.migrate();
        flyway.validate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture WHERE id=140", Integer.class))
                .isOne();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM \"flyway_schema_history\" WHERE \"version\"='139'"))
                .isEqualTo(before);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("140");
    }

    @Test
    void newDatabaseChoosesCanonicalRiskHistoryWithoutCreatingAnyTable() {
        var fresh =
                Flyway.configure()
                        .dataSource(
                                new DriverManagerDataSource(
                                        "jdbc:h2:mem:" + UUID.randomUUID(), "sa", ""))
                        .locations("filesystem:" + directory);
        IamV139MigrationResources.configure(fresh);
        assertThat(fresh.getResourceProvider().getResources("V139", new String[] {".sql"}))
                .extracting(r -> r.getFilename())
                .containsExactly(RISK);
    }

    @Test
    void classpathStartupUsesTheSameSelectionAndDoesNotApplyUnselectedSql() {
        history(COST);
        var configuration =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration", "classpath:db/bootstrap");
        IamV139MigrationResources.configure(configuration);
        assertThat(configuration.getResourceProvider().getResources("V139", new String[] {".sql"}))
                .extracting(r -> r.getFilename())
                .containsExactly(COST);
        assertThat(configuration.getResourceProvider().getResources("B", new String[] {".sql"}))
                .extracting(r -> r.getFilename())
                .contains("B120__general_ci_bootstrap.sql");
    }

    @Test
    void springBootCustomizerWorksWithTheNormalFlywayInitializer() {
        history(COST);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withUserConfiguration(IamMigrationResourcesConfiguration.class)
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
                                    .isInstanceOf(IamV139MigrationResources.class);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM fixture", Integer.class))
                                    .isOne();
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"checksum", "script", "description", "type", "success"})
    void rejectsUnrecognizedOrFailedHistory(String field) {
        history(COST);
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
                        + " WHERE \"version\"='139'");
        assertThatThrownBy(() -> IamV139MigrationResources.configure(configuration()))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("历史不是已核实");
        assertNoForwardChanges();
    }

    @Test
    void rejectsMultipleHistoryRowsForTheSameVersion() {
        history(COST);
        jdbc.update(
                """
                INSERT INTO "flyway_schema_history"
                ("installed_rank","version","description","type","script","checksum",
                 "installed_by","execution_time","success")
                SELECT 3,"version","description","type","script","checksum","installed_by",1,TRUE
                FROM "flyway_schema_history" WHERE "version"='139'
                """);
        assertThatThrownBy(() -> IamV139MigrationResources.configure(configuration()))
                .hasMessageContaining("历史不是已核实");
        assertNoForwardChanges();
    }

    @Test
    void refusesAnyChangeToEitherOriginalSql() throws Exception {
        history(COST);
        Files.writeString(directory.resolve(RISK), "SELECT 1;");
        assertThatThrownBy(() -> IamV139MigrationResources.configure(configuration()))
                .hasMessageContaining("已冻结");
        assertNoForwardChanges();
    }

    @Test
    void refusesMissingOriginalResource() throws Exception {
        history(COST);
        Files.delete(directory.resolve(RISK));
        assertThatThrownBy(() -> IamV139MigrationResources.configure(configuration()))
                .hasMessageContaining("原版 V139 文件各一份");
        assertNoForwardChanges();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void neverIgnoresMissingOrChangedOtherAppliedMigrations(boolean missing) throws Exception {
        history(COST);
        if (missing) Files.delete(directory.resolve("V138__fixture.sql"));
        else
            Files.writeString(
                    directory.resolve("V138__fixture.sql"), "CREATE TABLE other_fixture(id INT);");
        var configuration = configuration();
        IamV139MigrationResources.configure(configuration);
        assertThatThrownBy(() -> configuration.load().migrate())
                .isInstanceOf(FlywayException.class);
        assertNoForwardChanges();
    }

    @Test
    void neverIgnoresUnknownFutureHistory() {
        history(COST);
        jdbc.update(
                """
                INSERT INTO "flyway_schema_history"
                ("installed_rank","version","description","type","script","checksum",
                 "installed_by","execution_time","success")
                VALUES(3,'142','unknown','SQL','V142__unknown.sql',1,'fixture',1,TRUE)
                """);
        var configuration = configuration();
        IamV139MigrationResources.configure(configuration);
        assertThatThrownBy(() -> configuration.load().migrate())
                .isInstanceOf(FlywayException.class);
        assertNoForwardChanges();
    }

    void assertNoForwardChanges() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class)).isZero();
    }
}
