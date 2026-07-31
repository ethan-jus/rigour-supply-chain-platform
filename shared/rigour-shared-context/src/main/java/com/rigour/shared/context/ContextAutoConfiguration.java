package com.rigour.shared.context;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 注册请求上下文过滤器。
 * 使用显式自动配置而非包扫描，避免可选 shared 库被意外启用。
 */
@AutoConfiguration
@EnableConfigurationProperties(ContextTrustProperties.class)
public class ContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TrustedContextSigner trustedContextSigner(ContextTrustProperties properties) {
        return new TrustedContextSigner(properties);
    }

    @Bean
    @ConditionalOnMissingBean(name = "rigourRequestContextFilter")
    FilterRegistrationBean<RequestContextFilter> rigourRequestContextFilter(TrustedContextSigner contextSigner) {
        FilterRegistrationBean<RequestContextFilter> registration =
                new FilterRegistrationBean<>(new RequestContextFilter(contextSigner));
        registration.setName("rigourRequestContextFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
