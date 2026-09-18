package com.rigour.analytics.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Analytics BI 基础设施装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.analytics.infrastructure.persistence.mapper")
public class AnalyticsBiInfrastructureConfiguration {
    @Bean
    com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer supplyScopeLanguage() {
        return configuration ->
                configuration.setDefaultScriptingLanguage(
                        com.rigour.analytics.infrastructure.persistence.scope.BiScopedLanguageDriver
                                .class);
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<
                    com.rigour.analytics.infrastructure.persistence.scope.BiAuthorityFilter>
            authorityFilter(
                    com.rigour.analytics.infrastructure.persistence.scope.BiAuthorityProjector
                            projector,
                    com.rigour.analytics.infrastructure.persistence.scope.BiPeopleProjector
                            people) {
        var bean =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                        new com.rigour.analytics.infrastructure.persistence.scope.BiAuthorityFilter(
                                projector, people));
        bean.setName("biAuthorityFilter");
        bean.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 11);
        return bean;
    }

    @Bean
    Clock analyticsClock() {
        return Clock.systemUTC();
    }
}
