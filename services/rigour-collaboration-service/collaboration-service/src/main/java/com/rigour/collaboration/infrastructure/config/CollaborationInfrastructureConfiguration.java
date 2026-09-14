package com.rigour.collaboration.infrastructure.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 协作服务基础设施装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollaborationProperties.class)
public class CollaborationInfrastructureConfiguration {

    @Bean
    Clock collaborationClock() {
        return Clock.systemUTC();
    }
}
