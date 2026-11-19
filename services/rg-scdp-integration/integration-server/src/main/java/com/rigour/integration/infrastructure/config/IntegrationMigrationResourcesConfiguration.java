package com.rigour.integration.infrastructure.config;

import com.rigour.integration.infrastructure.persistence.migration.IntegrationV34MigrationResources;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 保留两条历史分支的校验值，只解析当前数据库对应的旧版本。 */
@Configuration(proxyBeanMethods = false)
public class IntegrationMigrationResourcesConfiguration {
    @Bean
    FlywayConfigurationCustomizer historicalMigrationResources() {
        return IntegrationV34MigrationResources::configure;
    }
}
