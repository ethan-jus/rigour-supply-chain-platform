package com.rigour.erp.infrastructure.config;

import com.rigour.erp.application.port.out.DhbProductMasterDataClient;
import com.rigour.erp.application.port.out.DhbProductSyncTargetDiscoveryClient;
import com.rigour.erp.application.port.out.DhbSupplyDataClient;
import com.rigour.erp.application.port.out.DhbSupplySyncTargetDiscoveryClient;
import com.rigour.erp.application.service.sync.ErpDataSyncScheduleProperties;
import com.rigour.erp.infrastructure.integration.HttpDhbProductMasterDataClient;
import com.rigour.erp.infrastructure.integration.HttpDhbProductSyncTargetDiscoveryClient;
import com.rigour.erp.infrastructure.integration.HttpDhbSupplyDataClient;
import com.rigour.erp.infrastructure.integration.HttpDhbSupplySyncTargetDiscoveryClient;
import com.rigour.integration.client.ConnectorSyncLeaseClient;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import java.time.Clock;
import java.time.Duration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** ERP 基础设施装配；订货宝客户端只在 Integration 中装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.erp.infrastructure.persistence.mapper")
@EnableConfigurationProperties({ProductMediaAccessProperties.class, ErpDataSyncScheduleProperties.class})
public class ErpInfrastructureConfiguration {

    @Bean
    Clock erpClock() {
        return Clock.systemUTC();
    }

    @Bean
    SimpleClientHttpRequestFactory erpIntegrationRequestFactory(
            @Value("${rigour.integration.http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${rigour.integration.http.read-timeout:30s}") Duration readTimeout) {
        positive(connectTimeout, "connect-timeout");
        positive(readTimeout, "read-timeout");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @Bean
    DhbProductMasterDataClient dhbProductMasterDataClient(
            TrustedContextSigner signer, ObjectMapper objectMapper,
            @Value("${rigour.integration.base-url:http://localhost:26882}") String integrationBaseUrl,
            SimpleClientHttpRequestFactory requestFactory) {
        return new HttpDhbProductMasterDataClient(RestClient.builder().requestFactory(requestFactory), signer, objectMapper,
                integrationBaseUrl);
    }

    @Bean
    BusinessDictionaryBatchClient businessDictionaryBatchClient(
            TrustedContextSigner signer,
            @Value("${rigour.business-settings.base-url:http://localhost:26892}") String baseUrl,
            SimpleClientHttpRequestFactory requestFactory) {
        return new BusinessDictionaryBatchClient(
                RestClient.builder().requestFactory(requestFactory), signer, baseUrl);
    }

    @Bean(destroyMethod = "close")
    ConnectorSyncLeaseClient connectorSyncLeaseClient(
            TrustedContextSigner signer,
            @Value("${rigour.integration.base-url:http://localhost:26882}") String baseUrl,
            SimpleClientHttpRequestFactory requestFactory) {
        return new ConnectorSyncLeaseClient(RestClient.builder().requestFactory(requestFactory), signer,
                baseUrl, "rigour-erp-core-service");
    }

    @Bean
    DhbSupplyDataClient dhbSupplyDataClient(
            TrustedContextSigner signer, ObjectMapper objectMapper,
            @Value("${rigour.integration.base-url:http://localhost:26882}") String integrationBaseUrl,
            SimpleClientHttpRequestFactory requestFactory) {
        return new HttpDhbSupplyDataClient(RestClient.builder().requestFactory(requestFactory), signer, objectMapper,
                integrationBaseUrl);
    }

    @Bean
    DhbProductSyncTargetDiscoveryClient dhbProductSyncTargetDiscoveryClient(
            TrustedContextSigner signer,
            @Value("${rigour.integration.base-url:http://localhost:26882}") String integrationBaseUrl,
            SimpleClientHttpRequestFactory requestFactory) {
        return new HttpDhbProductSyncTargetDiscoveryClient(RestClient.builder().requestFactory(requestFactory), signer,
                integrationBaseUrl);
    }

    @Bean
    DhbSupplySyncTargetDiscoveryClient dhbSupplySyncTargetDiscoveryClient(
            TrustedContextSigner signer,
            @Value("${rigour.integration.base-url:http://localhost:26882}") String integrationBaseUrl,
            SimpleClientHttpRequestFactory requestFactory) {
        return new HttpDhbSupplySyncTargetDiscoveryClient(RestClient.builder().requestFactory(requestFactory), signer,
                integrationBaseUrl);
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("ERP Integration " + name + " 必须在1ms到120s之间");
        }
    }
}
