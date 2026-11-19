package com.rigour.settings.infrastructure.config;

import com.rigour.settings.infrastructure.persistence.migration.FoundationV61MigrationResources;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 保留两条历史分支的校验值，只解析当前数据库对应的旧版本。 */
@Configuration(proxyBeanMethods = false)
public class FoundationMigrationResourcesConfiguration {
    @Bean
    FlywayConfigurationCustomizer historicalMigrationResources() {
        return FoundationV61MigrationResources::configure;
    }
}
