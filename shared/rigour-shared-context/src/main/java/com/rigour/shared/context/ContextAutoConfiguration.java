package com.rigour.shared.context;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

/**
 * 注册请求上下文过滤器。
 * 使用显式自动配置而非包扫描，避免可选 shared 库被意外启用。
 */
@AutoConfiguration
@EnableConfigurationProperties(ContextTrustProperties.class)
public class ContextAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ContextAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    TrustedContextSigner trustedContextSigner(ContextTrustProperties properties) {
        TrustedContextSigner signer = new TrustedContextSigner(properties);
        try {
            log.info("可信上下文HMAC配置已加载 keyId={} keyFingerprint={} maximumAgeMs={}",
                    signer.activeKeyId(), signer.activeKeyFingerprint(), signer.maximumAgeMillis());
        } catch (IllegalStateException exception) {
            // 空骨架服务可在未对外提供受保护接口时启动；一旦收到X-Rigour上下文会安全失败。
            log.warn("可信上下文HMAC尚未正确配置，受保护的下游请求将被拒绝 reason={}", exception.getMessage());
        }
        return signer;
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
