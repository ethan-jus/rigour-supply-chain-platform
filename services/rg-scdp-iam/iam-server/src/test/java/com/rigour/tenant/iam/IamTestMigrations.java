package com.rigour.tenant.iam;

import com.rigour.tenant.iam.infrastructure.persistence.migration.IamV139MigrationResources;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Uses the same frozen-history resolution as the application in disposable MySQL fixtures. */
public final class IamTestMigrations {
    private IamTestMigrations() {}

    public static FluentConfiguration configuration(DataSource dataSource) {
        FluentConfiguration configuration = Flyway.configure().dataSource(dataSource);
        IamV139MigrationResources.configure(configuration);
        return configuration;
    }
}
