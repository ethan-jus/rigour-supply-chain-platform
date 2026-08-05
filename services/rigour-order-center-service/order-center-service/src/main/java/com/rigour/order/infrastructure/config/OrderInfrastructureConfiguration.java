package com.rigour.order.infrastructure.config;

import com.rigour.order.application.port.out.DhbOrderSyncClient;
import com.rigour.order.infrastructure.integration.HttpDhbOrderSyncClient;
import com.rigour.shared.context.TrustedContextSigner;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** 订单中心基础设施装配；第三方连接器不在本服务装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.order.infrastructure.persistence.mapper")
public class OrderInfrastructureConfiguration {
    @Bean Clock orderClock() { return Clock.systemUTC(); }

    @Bean
    DhbOrderSyncClient dhbOrderSyncClient(
            TrustedContextSigner signer, ObjectMapper objectMapper,
            @Value("${rigour.integration.base-url:http://localhost:26882}") String integrationBaseUrl) {
        return new HttpDhbOrderSyncClient(RestClient.builder(), signer, objectMapper, integrationBaseUrl);
    }
}
