package com.rigour.settings.infrastructure.config;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 公共业务设置持久化装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.settings.infrastructure.persistence.mapper")
public class BusinessSettingsInfrastructureConfiguration {
    @Bean
    Clock businessSettingsClock() {
        return Clock.systemUTC();
    }
}
