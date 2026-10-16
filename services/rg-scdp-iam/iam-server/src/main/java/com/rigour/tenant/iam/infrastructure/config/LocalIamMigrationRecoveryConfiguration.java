package com.rigour.tenant.iam.infrastructure.config;

import com.rigour.tenant.iam.infrastructure.persistence.migration.KnownMissingV133MigrationStrategy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 原版 V133 同步前的本机临时启动入口；默认关闭，生产环境不装配。 */
@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
@ConditionalOnProperty(name = "rigour.iam.flyway.allow-missing-v133", havingValue = "true")
public class LocalIamMigrationRecoveryConfiguration {

    @Bean
    FlywayMigrationStrategy knownMissingV133MigrationStrategy() {
        return new KnownMissingV133MigrationStrategy();
    }
}
