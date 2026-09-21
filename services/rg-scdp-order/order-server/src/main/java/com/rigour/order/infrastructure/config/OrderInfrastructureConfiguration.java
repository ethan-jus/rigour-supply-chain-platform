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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
            @Value("${rigour.business-settings.base-url:http://rigour-business-settings-service}")
                    String settingsBaseUrl,
            SimpleClientHttpRequestFactory requestFactory,
            RestClient.Builder restClientBuilder) {
        return new HttpOrderRepairUnitDictionary(
                restClientBuilder.requestFactory(requestFactory), signer, settingsBaseUrl);
    }

    @Bean
    ErpOrderRepairCatalog erpOrderRepairCatalog(
            TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String erpBaseUrl,
            SimpleClientHttpRequestFactory requestFactory,
            RestClient.Builder restClientBuilder) {
        return new HttpErpOrderRepairCatalog(
                restClientBuilder.requestFactory(requestFactory), signer, erpBaseUrl);
    }

    @Bean
    HrEmployeeDisplayClient hrEmployeeDisplayClient(
            TrustedContextSigner signer,
            @Value("${rigour.hr.base-url:http://rigour-hr-payroll-service}") String hrBaseUrl,
            SimpleClientHttpRequestFactory requestFactory,
            RestClient.Builder restClientBuilder) {
        return new HttpHrEmployeeDisplayClient(
                restClientBuilder.requestFactory(requestFactory), signer, hrBaseUrl);
    }

    @Bean
    CrmCustomerAreaDisplayClient crmCustomerAreaDisplayClient(
            TrustedContextSigner signer,
            @Value("${rigour.crm.base-url:http://rigour-merchant-crm-service}") String crmBaseUrl,
            SimpleClientHttpRequestFactory requestFactory,
            RestClient.Builder restClientBuilder) {
        return new HttpCrmCustomerAreaDisplayClient(
                restClientBuilder.requestFactory(requestFactory), signer, crmBaseUrl);
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
