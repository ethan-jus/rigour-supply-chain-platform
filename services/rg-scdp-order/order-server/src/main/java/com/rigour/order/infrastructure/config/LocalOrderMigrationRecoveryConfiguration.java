package com.rigour.order.infrastructure.config;

import com.rigour.order.infrastructure.persistence.migration.KnownOrderV68MigrationStrategy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 临时恢复本机联调；默认关闭，DEV 单独运行或生产环境不装配。 */
@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
@ConditionalOnProperty(name = "rigour.order.flyway.allow-missing-v68", havingValue = "true")
public class LocalOrderMigrationRecoveryConfiguration {
    @Bean
    FlywayMigrationStrategy knownSettingsV68MigrationStrategy() {
        return new KnownOrderV68MigrationStrategy();
    }
}
