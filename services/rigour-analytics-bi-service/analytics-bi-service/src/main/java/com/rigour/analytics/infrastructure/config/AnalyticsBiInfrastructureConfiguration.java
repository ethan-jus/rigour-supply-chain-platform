package com.rigour.analytics.infrastructure.config;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Analytics BI 基础设施装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.analytics.infrastructure.persistence.mapper")
public class AnalyticsBiInfrastructureConfiguration {
    @Bean Clock analyticsClock() { return Clock.systemUTC(); }
}
