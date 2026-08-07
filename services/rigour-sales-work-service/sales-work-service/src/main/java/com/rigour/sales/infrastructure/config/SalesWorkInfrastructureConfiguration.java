package com.rigour.sales.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sales Work 基础设施最小装配；时钟作为依赖注入以便规则和用例可测试。 */
@Configuration(proxyBeanMethods = false)
public class SalesWorkInfrastructureConfiguration {

    @Bean
    Clock salesWorkClock() {
        return Clock.systemUTC();
    }
}
