package com.rigour.integration.infrastructure.config;

import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.port.out.FeishuJsapiClient;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import com.rigour.integration.application.service.dhb.DhbOrderSyncService;
import com.rigour.integration.application.service.dhb.ProductImageObjectKeyFactory;
import com.rigour.integration.application.service.feishu.FeishuJsapiSignService;
import com.rigour.integration.infrastructure.dhb.DhbClientAdapter;
import com.rigour.integration.infrastructure.dhb.DhbSecretResolver;
import com.rigour.integration.infrastructure.dhb.EnvDhbSecretResolver;
import com.rigour.integration.infrastructure.feishu.FeishuJsapiClientAdapter;
import com.rigour.integration.infrastructure.persistence.JdbcDhbIntegrationStore;
import com.rigour.integration.infrastructure.persistence.JdbcDhbSyncStore;
import com.rigour.integration.infrastructure.persistence.JdbcProductMediaSyncStore;
import com.rigour.integration.application.port.out.ProductMediaSyncStore;
import com.rigour.integration.infrastructure.media.ProductMediaSyncWorker;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/** Integration服务基础设施装配；持久化只属于Integration自己的Schema。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({DhbClientProperties.class, FeishuClientProperties.class,
        ProductMediaProperties.class})
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
    FeishuJsapiClient feishuJsapiClient(RestClient.Builder restClientBuilder,
                                        FeishuClientProperties properties) {
        return new FeishuJsapiClientAdapter(restClientBuilder, properties);
    }

    @Bean
    FeishuJsapiSignService feishuJsapiSignService(
            FeishuJsapiClient client, FeishuClientProperties properties) {
        return new FeishuJsapiSignService(client, properties.getAppId(),
                properties.allowedOriginValues(), properties.isAllowInsecureLan());
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
    ProductMediaSyncStore productMediaSyncStore(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcProductMediaSyncStore(jdbcTemplate, transactionManager);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService productMediaExecutor(ProductMediaProperties properties) {
        validateWorker(properties);
        return Executors.newFixedThreadPool(properties.getWorkerConcurrency(), runnable -> {
            Thread thread = new Thread(runnable, "rigour-product-media-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    DhbOrderSyncService dhbOrderSyncService(
            DhbSyncStore syncStore, DhbClient client) {
        return new DhbOrderSyncService(syncStore, client);
    }

    @Bean
    ProductImageObjectKeyFactory productImageObjectKeyFactory(ProductMediaProperties properties) {
        return new ProductImageObjectKeyFactory(properties.getCos().getObjectPrefix());
    }

    @Bean
    DhbIntegrationService dhbIntegrationService(
            DhbIntegrationStore store, DhbClient client,
            DhbOrderSyncService orderSyncService, ProductMediaStorage productMediaStorage,
            ProductImageObjectKeyFactory productImageObjectKeyFactory,
            ProductMediaSyncStore productMediaSyncStore) {
        return new DhbIntegrationService(store, client, orderSyncService, productMediaStorage,
                productImageObjectKeyFactory, productMediaSyncStore);
    }

    @Bean
    ProductMediaSyncWorker productMediaSyncWorker(
            ProductMediaSyncStore store, DhbClient client, ProductMediaStorage storage,
            ProductImageObjectKeyFactory keyFactory, ProductMediaProperties properties,
            ExecutorService productMediaExecutor) {
        return new ProductMediaSyncWorker(store, client, storage, keyFactory, properties,
                productMediaExecutor);
    }

    private static void validateWorker(ProductMediaProperties properties) {
        if (properties.getWorkerConcurrency() < 1 || properties.getWorkerConcurrency() > 32
                || properties.getWorkerPollIntervalMs() < 100
                || properties.getWorkerMaxAttempts() < 1 || properties.getWorkerMaxAttempts() > 8) {
            throw new IllegalStateException("商品图片 worker 参数无效：并发1-32、轮询间隔至少100ms、重试1-8次");
        }
    }
}
