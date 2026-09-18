package com.rigour.tenant.iam.client;

import com.rigour.shared.context.TrustedContextSigner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** 只由供应链业务服务显式依赖启用，不进入平台 starter，也不接管SCDP认证。 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SupplyAuthorizationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SupplyAuthorizationClient.class)
    SupplyAuthorizationClient supplyAuthorizationClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.supply-authorization.iam-base-url:${IAM_BASE_URL:http://rigour-tenant-iam-service:26881}}")
                    String base) {
        return new HttpSupplyAuthorizationClient(signer, base);
    }

    @Bean
    FilterRegistrationBean<SupplyAuthorizationFilter> supplyAuthorizationFilter(
            SupplyAuthorizationClient client) {
        var registration = new FilterRegistrationBean<>(new SupplyAuthorizationFilter(client));
        registration.setName("supplyAuthorizationFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
