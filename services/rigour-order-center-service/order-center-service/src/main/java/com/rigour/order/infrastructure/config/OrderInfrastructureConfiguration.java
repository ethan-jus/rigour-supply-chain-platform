package com.rigour.order.infrastructure.config;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 订单中心基础设施装配；第三方连接器不在本服务装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.order.infrastructure.persistence.mapper")
public class OrderInfrastructureConfiguration {
    @Bean Clock orderClock() { return Clock.systemUTC(); }
}
