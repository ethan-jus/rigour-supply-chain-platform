package com.rigour.integration.infrastructure.config;

import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.integration.application.port.out.CrmCustomerAttributionClient;
import com.rigour.integration.application.port.out.CrmCustomerProjectionClient;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbObjectCheckpointStore;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbOrchestrationLease;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.port.out.ErpDhbDomainSyncClient;
import com.rigour.integration.application.port.out.ErpProductProjectionClient;
import com.rigour.integration.application.port.out.ErpStockOutProjectionClient;
import com.rigour.integration.application.port.out.FeishuBitableClient;
import com.rigour.integration.application.port.out.FeishuImportStore;
import com.rigour.integration.application.port.out.FeishuJsapiClient;
import com.rigour.integration.application.port.out.HrEmployeeProjectionClient;
import com.rigour.integration.application.port.out.HrDhbStaffSyncClient;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.integration.application.port.out.ProductMediaSyncStore;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import com.rigour.integration.application.service.dhb.DhbAttachmentObjectKeyFactory;
import com.rigour.integration.application.service.dhb.DhbOrderSyncService;
import com.rigour.integration.application.service.dhb.DhbSyncOrchestrationProperties;
import com.rigour.integration.application.service.dhb.DhbSyncOrchestrationScheduler;
import com.rigour.integration.application.service.dhb.DhbSyncOrchestrationService;
import com.rigour.integration.application.service.dhb.ProductImageObjectKeyFactory;
import com.rigour.integration.application.service.feishu.FeishuJsapiSignService;
import com.rigour.integration.application.service.feishu.FeishuAttachmentImportService;
import com.rigour.integration.application.service.feishu.FeishuImportAsyncRunService;
import com.rigour.integration.application.service.feishu.FeishuImportBundleService;
import com.rigour.integration.application.service.feishu.FeishuAttachmentObjectKeyFactory;
import com.rigour.integration.infrastructure.feishu.FeishuBitableClientAdapter;
import com.rigour.integration.infrastructure.dhb.DhbClientAdapter;
import com.rigour.integration.infrastructure.dhb.DhbSecretResolver;
import com.rigour.integration.infrastructure.dhb.EnvDhbSecretResolver;
import com.rigour.integration.infrastructure.domain.HttpCrmDhbDomainSyncClient;
import com.rigour.integration.infrastructure.domain.HttpCrmCustomerAttributionClient;
import com.rigour.integration.infrastructure.domain.HttpCrmCustomerProjectionClient;
import com.rigour.integration.infrastructure.domain.HttpErpDhbDomainSyncClient;
import com.rigour.integration.infrastructure.domain.HttpErpProductProjectionClient;
import com.rigour.integration.infrastructure.domain.HttpErpStockOutProjectionClient;
import com.rigour.integration.infrastructure.domain.HttpHrEmployeeProjectionClient;
import com.rigour.integration.infrastructure.domain.HttpHrDhbStaffSyncClient;
import com.rigour.integration.infrastructure.domain.HttpOrderSalesOrderProjectionClient;
import com.rigour.integration.infrastructure.feishu.FeishuJsapiClientAdapter;
import com.rigour.integration.infrastructure.lease.DhbConnectorLeaseProperties;
import com.rigour.integration.infrastructure.media.ProductMediaSyncWorker;
import com.rigour.integration.infrastructure.persistence.mapper.DhbConnectorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.ExternalObjectMappingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationDeadLetterMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportBatchMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportIssueMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportRawRowMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportTableMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportTemplateDependencyMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFeishuImportTemplateMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationFieldMappingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationManualResolutionMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationOrderMirrorMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationOutboxEventMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationProductMediaItemMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationProductMediaJobMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationRawLandingMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationReconciliationCaseMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncCheckpointMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncLogMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncRunMapper;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationSyncTaskMapper;
import com.rigour.integration.infrastructure.persistence.repository.MybatisPlusDhbIntegrationStore;
import com.rigour.integration.infrastructure.persistence.repository.MybatisPlusDhbSyncStore;
import com.rigour.integration.infrastructure.persistence.repository.MybatisPlusFeishuImportStore;
import com.rigour.integration.infrastructure.persistence.repository.MybatisPlusProductMediaSyncStore;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/** Integration服务基础设施装配；持久化只属于Integration自己的Schema。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@MapperScan("com.rigour.integration.infrastructure.persistence.mapper")
@EnableConfigurationProperties({DhbClientProperties.class, FeishuClientProperties.class,
        FeishuImportProperties.class, ProductMediaProperties.class, DhbConnectorLeaseProperties.class,
        DhbSyncOrchestrationProperties.class})
public final class IntegrationInfrastructureConfiguration {

    @Bean
    @ConditionalOnMissingBean(DhbSecretResolver.class)
    DhbSecretResolver dhbSecretResolver() {
        return new EnvDhbSecretResolver();
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
    FeishuBitableClient feishuBitableClient(RestClient.Builder restClientBuilder,
                                            FeishuClientProperties properties) {
        return new FeishuBitableClientAdapter(restClientBuilder, properties);
    }

    @Bean
    FeishuJsapiSignService feishuJsapiSignService(
            FeishuJsapiClient client, FeishuClientProperties properties) {
        return new FeishuJsapiSignService(client, properties.getAppId(),
                properties.allowedOriginValues(), properties.isAllowInsecureLan());
    }

    @Bean
    FeishuImportStore feishuImportStore(
            IntegrationFeishuImportBatchMapper batchMapper,
            IntegrationFeishuImportTableMapper tableMapper,
            IntegrationFeishuImportIssueMapper issueMapper,
            IntegrationFeishuImportRawRowMapper rawRowMapper,
            IntegrationFeishuImportTemplateMapper templateMapper,
            IntegrationFeishuImportTemplateDependencyMapper templateDependencyMapper,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new MybatisPlusFeishuImportStore(batchMapper, tableMapper, issueMapper, rawRowMapper,
                templateMapper, templateDependencyMapper, jdbcTemplate, transactionManager, objectMapper);
    }

    @Bean
    FeishuImportBundleService feishuImportBundleService(
            FeishuImportStore store,
            OrderSalesOrderProjectionClient orderSalesOrderProjectionClient,
            HrEmployeeProjectionClient hrEmployeeProjectionClient,
            CrmCustomerProjectionClient crmCustomerProjectionClient,
            ErpProductProjectionClient erpProductProjectionClient,
            BusinessDictionaryBatchClient businessDictionaryBatchClient,
            FeishuAttachmentImportService feishuAttachmentImportService,
            FeishuImportProperties properties,
            Clock clock) {
        return new FeishuImportBundleService(store, orderSalesOrderProjectionClient,
                hrEmployeeProjectionClient, crmCustomerProjectionClient, erpProductProjectionClient,
                businessDictionaryBatchClient, feishuAttachmentImportService, properties, clock);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService feishuImportExecutor(
            @Value("${rigour.integration.feishu.import-bundle.worker-concurrency:2}") int workerConcurrency) {
        int concurrency = Math.max(1, Math.min(workerConcurrency, 8));
        return Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "rigour-feishu-import-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    FeishuImportAsyncRunService feishuImportAsyncRunService(
            FeishuImportBundleService service,
            FeishuImportStore store,
            @Qualifier("feishuImportExecutor") ExecutorService feishuImportExecutor,
            Clock clock) {
        return new FeishuImportAsyncRunService(service, store, feishuImportExecutor, clock);
    }

    @Bean
    FeishuAttachmentImportService feishuAttachmentImportService(
            FeishuImportStore store,
            FeishuBitableClient feishuBitableClient,
            ProductMediaStorage productMediaStorage,
            FeishuAttachmentObjectKeyFactory feishuAttachmentObjectKeyFactory,
            FeishuImportProperties properties) {
        return new FeishuAttachmentImportService(store, feishuBitableClient, productMediaStorage,
                feishuAttachmentObjectKeyFactory, properties.getDefaultSourceUrl());
    }

    @Bean
    DhbIntegrationStore dhbIntegrationStore(
            DhbConnectorMapper connectorMapper,
            IntegrationSyncTaskMapper taskMapper,
            IntegrationFieldMappingMapper fieldMappingMapper,
            IntegrationOrderMirrorMapper orderMirrorMapper,
            IntegrationSyncLogMapper syncLogMapper,
            ExternalObjectMappingMapper externalObjectMappingMapper,
            IntegrationSyncRunMapper syncRunMapper,
            IntegrationDeadLetterMapper deadLetterMapper,
            IntegrationReconciliationCaseMapper reconciliationCaseMapper,
            IntegrationManualResolutionMapper manualResolutionMapper,
            IntegrationRawLandingMapper rawLandingMapper,
            PlatformTransactionManager transactionManager,
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new MybatisPlusDhbIntegrationStore(connectorMapper, taskMapper,
                fieldMappingMapper, orderMirrorMapper, syncLogMapper,
                externalObjectMappingMapper, syncRunMapper, deadLetterMapper,
                reconciliationCaseMapper, manualResolutionMapper, rawLandingMapper,
                transactionManager, objectMapper);
    }

    @Bean
    DhbSyncStore dhbSyncStore(
            DhbConnectorMapper connectorMapper,
            IntegrationSyncTaskMapper taskMapper,
            IntegrationSyncCheckpointMapper checkpointMapper,
            IntegrationSyncRunMapper runMapper,
            IntegrationRawLandingMapper rawLandingMapper,
            IntegrationOrderMirrorMapper orderMirrorMapper,
            IntegrationOutboxEventMapper outboxEventMapper,
            ExternalObjectMappingMapper externalObjectMappingMapper,
            IntegrationDeadLetterMapper deadLetterMapper,
            IntegrationReconciliationCaseMapper reconciliationCaseMapper,
            IntegrationManualResolutionMapper manualResolutionMapper,
            IntegrationSyncLogMapper syncLogMapper,
            PlatformTransactionManager transactionManager,
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new MybatisPlusDhbSyncStore(connectorMapper, taskMapper, checkpointMapper,
                runMapper, rawLandingMapper, orderMirrorMapper, outboxEventMapper,
                externalObjectMappingMapper, deadLetterMapper, reconciliationCaseMapper,
                manualResolutionMapper, syncLogMapper, transactionManager, objectMapper);
    }

    @Bean
    ProductMediaSyncStore productMediaSyncStore(
            IntegrationProductMediaJobMapper jobMapper,
            IntegrationProductMediaItemMapper itemMapper,
            DhbConnectorMapper connectorMapper,
            PlatformTransactionManager transactionManager) {
        return new MybatisPlusProductMediaSyncStore(jobMapper, itemMapper,
                connectorMapper, transactionManager);
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
            DhbSyncStore syncStore, DhbClient client,
            OrderSalesOrderProjectionClient orderSalesOrderProjectionClient,
            ErpStockOutProjectionClient erpStockOutProjectionClient,
            HrDhbStaffSyncClient hrDhbStaffSyncClient,
            BusinessDictionaryBatchClient businessDictionaryBatchClient,
            ProductMediaStorage productMediaStorage,
            DhbAttachmentObjectKeyFactory dhbAttachmentObjectKeyFactory,
            CrmCustomerAttributionClient crmCustomerAttributionClient,
            @Value("${rigour.integration.dhb.order.detail-concurrency:3}") int detailConcurrency) {
        return new DhbOrderSyncService(syncStore, client, orderSalesOrderProjectionClient,
                erpStockOutProjectionClient, hrDhbStaffSyncClient,
                businessDictionaryBatchClient, detailConcurrency,
                productMediaStorage, dhbAttachmentObjectKeyFactory, crmCustomerAttributionClient);
    }

    @Bean
    OrderSalesOrderProjectionClient orderSalesOrderProjectionClient(
            SimpleClientHttpRequestFactory domainSyncRequestFactory, TrustedContextSigner signer,
            @Value("${rigour.order.base-url:http://rigour-order-center-service}") String orderBaseUrl,
            RestClient.Builder restClientBuilder) {
        return new HttpOrderSalesOrderProjectionClient(
                restClientBuilder.requestFactory(domainSyncRequestFactory), signer,
                orderBaseUrl);
    }

    @Bean
    BusinessDictionaryBatchClient businessDictionaryBatchClient(
            TrustedContextSigner signer,
            @Value("${rigour.business-settings.base-url:http://rigour-business-settings-service}")
                    String baseUrl,
            @Value("${rigour.integration.dictionary-http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${rigour.integration.dictionary-http.read-timeout:30s}") Duration readTimeout,
            RestClient.Builder restClientBuilder) {
        return new BusinessDictionaryBatchClient(
                restClientBuilder.requestFactory(requestFactory(connectTimeout, readTimeout)),
                signer, baseUrl);
    }

    @Bean
    ErpStockOutProjectionClient erpStockOutProjectionClient(
            SimpleClientHttpRequestFactory domainSyncRequestFactory, TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erpBaseUrl,
            RestClient.Builder restClientBuilder) {
        return new HttpErpStockOutProjectionClient(
                restClientBuilder.requestFactory(domainSyncRequestFactory), signer, erpBaseUrl);
    }

    @Bean
    SimpleClientHttpRequestFactory domainSyncRequestFactory(
            @Value("${rigour.integration.domain-http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${rigour.integration.domain-http.read-timeout:120s}") Duration readTimeout) {
        return requestFactory(connectTimeout, readTimeout);
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @Bean
    ErpDhbDomainSyncClient erpDhbDomainSyncClient(
            SimpleClientHttpRequestFactory domainSyncRequestFactory, TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erpBaseUrl,
            RestClient.Builder restClientBuilder) {
        return new HttpErpDhbDomainSyncClient(
                restClientBuilder.requestFactory(domainSyncRequestFactory), signer, erpBaseUrl);
    }

    @Bean
    CrmDhbDomainSyncClient crmDhbDomainSyncClient(
            TrustedContextSigner signer,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String crmBaseUrl,
            @Value("${rigour.integration.crm-sync-http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${rigour.integration.crm-sync-http.read-timeout:600s}") Duration readTimeout,
            RestClient.Builder restClientBuilder) {
        // Initial customer/address reconciliation can exceed the normal domain request timeout.
        return new HttpCrmDhbDomainSyncClient(
                restClientBuilder.requestFactory(requestFactory(connectTimeout, readTimeout)), signer,
                crmBaseUrl);
    }

    @Bean
    HrDhbStaffSyncClient hrDhbStaffSyncClient(
            SimpleClientHttpRequestFactory domainSyncRequestFactory, TrustedContextSigner signer,
            @Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String hrBaseUrl,
            RestClient.Builder restClientBuilder) {
        return new HttpHrDhbStaffSyncClient(
                restClientBuilder.requestFactory(domainSyncRequestFactory), signer, hrBaseUrl);
    }

    @Bean
    HrEmployeeProjectionClient hrEmployeeProjectionClient(
            SimpleClientHttpRequestFactory domainSyncRequestFactory, TrustedContextSigner signer,
            @Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String hrBaseUrl,
            RestClient.Builder restClientBuilder) {
        return new HttpHrEmployeeProjectionClient(
                restClientBuilder.requestFactory(domainSyncRequestFactory), signer, hrBaseUrl);
    }

    @Bean
    CrmCustomerProjectionClient crmCustomerProjectionClient(
            SimpleClientHttpRequestFactory domainSyncRequestFactory, TrustedContextSigner signer,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String crmBaseUrl,
            RestClient.Builder restClientBuilder) {
        return new HttpCrmCustomerProjectionClient(
                restClientBuilder.requestFactory(domainSyncRequestFactory), signer, crmBaseUrl);
    }

    @Bean
    CrmCustomerAttributionClient crmCustomerAttributionClient(
            SimpleClientHttpRequestFactory domainSyncRequestFactory, TrustedContextSigner signer,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String crmBaseUrl,
            RestClient.Builder restClientBuilder) {
        return new HttpCrmCustomerAttributionClient(
                restClientBuilder.requestFactory(domainSyncRequestFactory), signer, crmBaseUrl);
    }

    @Bean
    ErpProductProjectionClient erpProductProjectionClient(
            SimpleClientHttpRequestFactory domainSyncRequestFactory, TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erpBaseUrl,
            RestClient.Builder restClientBuilder) {
        return new HttpErpProductProjectionClient(
                restClientBuilder.requestFactory(domainSyncRequestFactory), signer, erpBaseUrl);
    }

    @Bean
    Clock integrationClock() {
        return Clock.systemUTC();
    }

    @Bean
    DhbSyncOrchestrationService dhbSyncOrchestrationService(
            DhbIntegrationStore store,
            ErpDhbDomainSyncClient erpClient,
            CrmDhbDomainSyncClient crmClient,
            HrDhbStaffSyncClient hrEmployeeClient,
            DhbClient dhbClient,
            DhbOrderSyncService orderSyncService,
            DhbObjectCheckpointStore dhbObjectCheckpointStore,
            BusinessDictionaryBatchClient businessDictionaryBatchClient,
            DhbOrchestrationLease dhbOrchestrationLease,
            DhbSyncOrchestrationProperties properties,
            Clock clock,
            tools.jackson.databind.ObjectMapper objectMapper) {
        return new DhbSyncOrchestrationService(store, erpClient, crmClient, hrEmployeeClient, dhbClient,
                orderSyncService, dhbObjectCheckpointStore, businessDictionaryBatchClient,
                dhbOrchestrationLease, properties, clock, objectMapper);
    }

    @Bean
    DhbSyncOrchestrationScheduler dhbSyncOrchestrationScheduler(
            DhbSyncOrchestrationService service, DhbSyncOrchestrationProperties properties,
            com.rigour.integration.application.service.dhb.DhbContinuousOrderSyncService continuous) {
        return new DhbSyncOrchestrationScheduler(service, properties,continuous);
    }

    @Bean
    ProductImageObjectKeyFactory productImageObjectKeyFactory(ProductMediaProperties properties) {
        return new ProductImageObjectKeyFactory(properties.getCos().getObjectPrefix());
    }

    @Bean
    DhbAttachmentObjectKeyFactory dhbAttachmentObjectKeyFactory(ProductMediaProperties properties) {
        return new DhbAttachmentObjectKeyFactory(properties.getFundAttachmentPrefix());
    }

    @Bean
    FeishuAttachmentObjectKeyFactory feishuAttachmentObjectKeyFactory(ProductMediaProperties properties) {
        return new FeishuAttachmentObjectKeyFactory(properties.getFeishuAttachmentPrefix());
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
            @Qualifier("productMediaExecutor") ExecutorService productMediaExecutor) {
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
