package com.rigour.sales.infrastructure.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Sales Work 基础设施最小装配；时钟作为依赖注入以便规则和用例可测试。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AmapProperties.class, SalesRecordingProperties.class})
public class SalesWorkInfrastructureConfiguration {

    @Bean
    Clock salesWorkClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder salesWorkRestClientBuilder() {
        return RestClient.builder();
    }
}
