package com.rigour.settings.infrastructure.config;

import com.rigour.settings.infrastructure.persistence.migration.KnownSettingsV57MigrationStrategy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 临时恢复本机联调；默认关闭，DEV 单独运行或生产环境不装配。 */
@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
@ConditionalOnProperty(
        name = "rigour.settings.flyway.allow-known-history-conflicts",
        havingValue = "true")
public class LocalSettingsMigrationRecoveryConfiguration {
    @Bean
    FlywayMigrationStrategy knownSettingsV57MigrationStrategy() {
        return new KnownSettingsV57MigrationStrategy();
    }
}
