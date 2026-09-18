package com.rigour.settings.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 公共业务设置持久化装配。 */
@Configuration(proxyBeanMethods = false)
public class BusinessSettingsInfrastructureConfiguration {
    @Bean
    Clock businessSettingsClock() {
        return Clock.systemUTC();
    }
}
