package com.rigour.integration.infrastructure.config;

import com.rigour.integration.application.port.out.DinghuobaoIntegrationStore;
import com.rigour.integration.application.port.out.DinghuobaoClient;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoIntegrationService;
import com.rigour.integration.infrastructure.dinghuobao.DinghuobaoClientAdapter;
import com.rigour.integration.infrastructure.dinghuobao.DinghuobaoSecretResolver;
import com.rigour.integration.infrastructure.dinghuobao.EnvironmentDinghuobaoSecretResolver;
import com.rigour.integration.infrastructure.persistence.JdbcDinghuobaoIntegrationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/** Integration服务基础设施装配；持久化只属于Integration自己的Schema。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DinghuobaoClientProperties.class)
public final class IntegrationInfrastructureConfiguration {

    @Bean
    @ConditionalOnMissingBean(DinghuobaoSecretResolver.class)
    DinghuobaoSecretResolver dinghuobaoSecretResolver(Environment environment) {
        return new EnvironmentDinghuobaoSecretResolver(environment);
    }

    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder dinghuobaoRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    DinghuobaoClient dinghuobaoClient(RestClient.Builder restClientBuilder,
                                      DinghuobaoSecretResolver secretResolver,
                                      DinghuobaoClientProperties properties) {
        return new DinghuobaoClientAdapter(restClientBuilder, secretResolver, properties);
    }

    @Bean
    DinghuobaoIntegrationStore dinghuobaoIntegrationStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcDinghuobaoIntegrationStore(jdbcTemplate, transactionManager);
    }

    @Bean
    DinghuobaoIntegrationService dinghuobaoIntegrationService(
            DinghuobaoIntegrationStore store, DinghuobaoClient client) {
        return new DinghuobaoIntegrationService(store, client);
    }
}
