package com.rigour.integration.infrastructure.config;

import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import com.rigour.integration.application.service.dhb.DhbOrderSyncService;
import com.rigour.integration.infrastructure.dhb.DhbClientAdapter;
import com.rigour.integration.infrastructure.dhb.DhbSecretResolver;
import com.rigour.integration.infrastructure.dhb.EnvDhbSecretResolver;
import com.rigour.integration.infrastructure.persistence.JdbcDhbIntegrationStore;
import com.rigour.integration.infrastructure.persistence.JdbcDhbSyncStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/** Integration服务基础设施装配；持久化只属于Integration自己的Schema。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DhbClientProperties.class)
public final class IntegrationInfrastructureConfiguration {

    @Bean
    @ConditionalOnMissingBean(DhbSecretResolver.class)
    DhbSecretResolver dhbSecretResolver() {
        return new EnvDhbSecretResolver();
    }

    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder dhbRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    DhbClient dhbClient(RestClient.Builder restClientBuilder,
                                      DhbSecretResolver secretResolver,
                                      DhbClientProperties properties) {
        return new DhbClientAdapter(restClientBuilder, secretResolver, properties);
    }

    @Bean
    DhbIntegrationStore dhbIntegrationStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new JdbcDhbIntegrationStore(jdbcTemplate, transactionManager, objectMapper);
    }

    @Bean
    DhbSyncStore dhbSyncStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new JdbcDhbSyncStore(jdbcTemplate, transactionManager, objectMapper);
    }

    @Bean
    DhbOrderSyncService dhbOrderSyncService(
            DhbSyncStore syncStore, DhbClient client) {
        return new DhbOrderSyncService(syncStore, client);
    }

    @Bean
    DhbIntegrationService dhbIntegrationService(
            DhbIntegrationStore store, DhbClient client,
            DhbOrderSyncService orderSyncService) {
        return new DhbIntegrationService(store, client, orderSyncService);
    }
}
