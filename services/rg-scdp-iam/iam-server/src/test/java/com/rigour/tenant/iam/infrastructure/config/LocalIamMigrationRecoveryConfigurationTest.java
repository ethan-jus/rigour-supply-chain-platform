package com.rigour.tenant.iam.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalIamMigrationRecoveryConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(LocalIamMigrationRecoveryConfiguration.class);

    @Test
    void requiresExplicitLocalOptIn() {
        context.withPropertyValues(
                        "spring.profiles.active=dev,local",
                        "rigour.iam.flyway.allow-missing-v133=true")
                .run(result -> assertThat(result).hasSingleBean(FlywayMigrationStrategy.class));
    }

    @Test
    void disabledByDefault() {
        context.withPropertyValues("spring.profiles.active=dev,local")
                .run(result -> assertThat(result).doesNotHaveBean(FlywayMigrationStrategy.class));
    }

    @Test
    void unavailableForSharedDevWithoutLocalProfile() {
        context.withPropertyValues(
                        "spring.profiles.active=dev", "rigour.iam.flyway.allow-missing-v133=true")
                .run(result -> assertThat(result).doesNotHaveBean(FlywayMigrationStrategy.class));
    }

    @Test
    void unavailableForProductionEvenWithLocalProfile() {
        context.withPropertyValues(
                        "spring.profiles.active=local,prod",
                        "rigour.iam.flyway.allow-missing-v133=true")
                .run(result -> assertThat(result).doesNotHaveBean(FlywayMigrationStrategy.class));
    }
}
