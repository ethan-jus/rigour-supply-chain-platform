package com.rigour.sales.infrastructure.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sales Work 基础设施最小装配；时钟作为依赖注入以便规则和用例可测试。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AmapProperties.class,
        SalesRecordingProperties.class,
        SalesEvidenceProperties.class
})
public class SalesWorkInfrastructureConfiguration {

    @Bean
    Clock salesWorkClock() {
        return Clock.systemUTC();
    }
}
