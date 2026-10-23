package com.rigour.tenant.iam.infrastructure.config;

import com.rigour.tenant.iam.infrastructure.persistence.migration.IamV139MigrationResources;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DEV 自动迁移与独立发布工具使用相同的历史选择，不修改任何已执行记录。 */
@Configuration(proxyBeanMethods = false)
public class IamMigrationResourcesConfiguration {
    @Bean
    FlywayConfigurationCustomizer iamHistoricalMigrationResources() {
        return IamV139MigrationResources::configure;
    }
}
