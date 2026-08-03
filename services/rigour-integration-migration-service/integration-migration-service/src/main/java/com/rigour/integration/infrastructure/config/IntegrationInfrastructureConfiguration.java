package com.rigour.integration.infrastructure.config;

import com.rigour.integration.application.port.out.DinghuobaoIntegrationStore;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoIntegrationService;
import com.rigour.integration.infrastructure.persistence.JdbcDinghuobaoIntegrationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Integration服务基础设施装配；持久化只属于Integration自己的Schema。 */
@Configuration(proxyBeanMethods = false)
public final class IntegrationInfrastructureConfiguration {

    @Bean
    DinghuobaoIntegrationStore dinghuobaoIntegrationStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcDinghuobaoIntegrationStore(jdbcTemplate, transactionManager);
    }

    @Bean
    DinghuobaoIntegrationService dinghuobaoIntegrationService(
            DinghuobaoIntegrationStore store) {
        return new DinghuobaoIntegrationService(store);
    }
}
