package com.rigour.settings.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalSettingsMigrationRecoveryConfigurationTest {
    final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(LocalSettingsMigrationRecoveryConfiguration.class);

    @Test
    void localWithExplicitFlagEnablesRecovery() {
        runner.withPropertyValues(
                        "spring.profiles.active=dev,local",
                        "rigour.settings.flyway.allow-known-history-conflicts=true")
                .run(c -> assertThat(c).hasSingleBean(FlywayMigrationStrategy.class));
    }

    @Test
    void disabledByDefault() {
        runner.withPropertyValues("spring.profiles.active=dev,local")
                .run(c -> assertThat(c).doesNotHaveBean(FlywayMigrationStrategy.class));
    }

    @Test
    void sharedDevCannotEnableRecovery() {
        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "rigour.settings.flyway.allow-known-history-conflicts=true")
                .run(c -> assertThat(c).doesNotHaveBean(FlywayMigrationStrategy.class));
    }

    @Test
    void productionCannotEnableRecovery() {
        runner.withPropertyValues(
                        "spring.profiles.active=local,prod",
                        "rigour.settings.flyway.allow-known-history-conflicts=true")
                .run(c -> assertThat(c).doesNotHaveBean(FlywayMigrationStrategy.class));
    }
}
