package com.rigour.hr.infrastructure.config;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HR 服务基础设施装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.hr.infrastructure.persistence.mapper")
public class HrInfrastructureConfiguration {
    @Bean
    Clock hrClock() {
        return Clock.systemUTC();
    }
}
