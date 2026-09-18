package com.rigour.order.infrastructure.config;

import com.rigour.order.application.port.out.CrmCustomerAreaDisplayClient;
import com.rigour.order.application.port.out.ErpOrderRepairCatalog;
import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.order.application.port.out.OrderRepairUnitDictionary;
import com.rigour.order.infrastructure.integration.HttpCrmCustomerAreaDisplayClient;
import com.rigour.order.infrastructure.integration.HttpErpOrderRepairCatalog;
import com.rigour.order.infrastructure.integration.HttpHrEmployeeDisplayClient;
import com.rigour.order.infrastructure.integration.HttpOrderRepairUnitDictionary;
import com.rigour.shared.context.TrustedContextSigner;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;

/** 订单中心基础设施装配；第三方连接器不在本服务装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.order.infrastructure.persistence.mapper")
@EnableConfigurationProperties(FundAttachmentAccessProperties.class)
public class OrderInfrastructureConfiguration {
    @Bean
    Clock orderClock() {
        return Clock.systemUTC();
    }

    @Bean
    SimpleClientHttpRequestFactory orderOutboundRequestFactory(
            @Value("${rigour.order.http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${rigour.order.http.read-timeout:30s}") Duration readTimeout) {
        positive(connectTimeout, "connect-timeout");
        positive(readTimeout, "read-timeout");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }

    @Bean
    OrderRepairUnitDictionary orderRepairUnitDictionary(
            TrustedContextSigner signer,
            @Value("${rigour.business-settings.base-url:http://localhost:26892}")
                    String settingsBaseUrl,
            SimpleClientHttpRequestFactory requestFactory,
            Environment environment) {
        return new HttpOrderRepairUnitDictionary(
                RestClient.builder().requestFactory(requestFactory),
                signer,
                localServiceUrl(
                        environment, settingsBaseUrl, "rigour-business-settings-service", 26892));
    }

    @Bean
    ErpOrderRepairCatalog erpOrderRepairCatalog(
            TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:http://localhost:26884}") String erpBaseUrl,
            SimpleClientHttpRequestFactory requestFactory,
            Environment environment) {
        return new HttpErpOrderRepairCatalog(
                RestClient.builder().requestFactory(requestFactory),
                signer,
                localServiceUrl(environment, erpBaseUrl, "rigour-erp-core-service", 26884));
    }

    @Bean
    HrEmployeeDisplayClient hrEmployeeDisplayClient(
            TrustedContextSigner signer,
            @Value("${rigour.hr.base-url:http://localhost:26889}") String hrBaseUrl,
            SimpleClientHttpRequestFactory requestFactory,
            Environment environment) {
        return new HttpHrEmployeeDisplayClient(
                RestClient.builder().requestFactory(requestFactory),
                signer,
                localServiceUrl(environment, hrBaseUrl, "rigour-hr-payroll-service", 26889));
    }

    @Bean
    CrmCustomerAreaDisplayClient crmCustomerAreaDisplayClient(
            TrustedContextSigner signer,
            @Value("${rigour.crm.base-url:http://localhost:26883}") String crmBaseUrl,
            SimpleClientHttpRequestFactory requestFactory,
            Environment environment) {
        return new HttpCrmCustomerAreaDisplayClient(
                RestClient.builder().requestFactory(requestFactory),
                signer,
                localServiceUrl(environment, crmBaseUrl, "rigour-merchant-crm-service", 26883));
    }

    // Match Integration's local-only routing without overriding an explicit developer endpoint.
    static String localServiceUrl(
            Environment environment, String configured, String serviceHost, int port) {
        if (!environment.acceptsProfiles(Profiles.of("local")) || configured == null)
            return configured;
        try {
            URI uri = new URI(configured);
            if (!serviceHost.equalsIgnoreCase(uri.getHost())) return configured;
            return new URI(
                            uri.getScheme(),
                            uri.getUserInfo(),
                            "127.0.0.1",
                            port,
                            uri.getPath(),
                            uri.getQuery(),
                            uri.getFragment())
                    .toString();
        } catch (URISyntaxException ignored) {
            return configured;
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("Order HTTP " + name + " 必须在1ms到120s之间");
        }
    }
}
