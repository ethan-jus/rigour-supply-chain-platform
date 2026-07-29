package com.rigour.shared.logging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 在上下文过滤器之后注册访问日志过滤器，确保日志可读取 requestId 和 tenantId。
 */
@AutoConfiguration
public class LoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "rigourRequestLoggingFilter")
    FilterRegistrationBean<RequestLoggingFilter> rigourRequestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter());
        registration.setName("rigourRequestLoggingFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
