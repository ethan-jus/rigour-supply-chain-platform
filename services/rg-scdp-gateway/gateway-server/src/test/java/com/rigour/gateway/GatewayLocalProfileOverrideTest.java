package com.rigour.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务间寻址完全由 Nacos 注册中心承担：local/dev 都不再声明静态服务地址，
 * 只继承 dev 的注册配置。一旦有人重新引入 *_SERVICE_URL 静态表，这里会先失败，
 * 而不是等联调时才发现网关把请求发去了旧实例。
 */
class GatewayLocalProfileOverrideTest {
    /** 只用来看配置数据装载结果，不启用任何自动配置，也不参与其他测试的上下文搜索。 */
    @Configuration
    static class BareConfiguration {
    }

    @Test
    void localProfileInheritsRegistryAndKeepsNoStaticServiceUrls() {
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(BareConfiguration.class)
                        .profiles("local")
                        .web(WebApplicationType.NONE)
                        .run()) {
            var environment = context.getEnvironment();

            // 注册中心仍然继承 dev 的台式机地址，本机不装中间件；本机实例注册进个人/共享命名空间
            assertThat(environment.getProperty("spring.cloud.nacos.server-addr"))
                    .isEqualTo("192.168.12.7:18848");
            assertThat(environment.getProperty("spring.cloud.nacos.discovery.namespace")).isNotBlank();
            assertThat(environment.getProperty("spring.cloud.nacos.discovery.namespace"))
                    .isEqualTo(environment.getProperty("spring.cloud.nacos.config.namespace"));
            assertThat(environment.getProperty("spring.cloud.nacos.discovery.register-enabled"))
                    .isEqualTo("true");

            // 静态服务地址表已移除，服务间按服务名解析实例
            assertThat(environment.getProperty("IAM_SERVICE_URL")).isNull();
            assertThat(environment.getProperty("ERP_SERVICE_URL")).isNull();
            assertThat(environment.getProperty("ORDER_SERVICE_URL")).isNull();
        }
    }

    @Test
    void devProfileRegistersWithoutStaticServiceUrls() {
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(BareConfiguration.class)
                        .profiles("dev")
                        .web(WebApplicationType.NONE)
                        .run()) {
            var environment = context.getEnvironment();

            assertThat(environment.getProperty("spring.cloud.nacos.discovery.register-enabled"))
                    .isEqualTo("true");
            assertThat(environment.getProperty("ERP_SERVICE_URL")).isNull();
            assertThat(environment.getProperty("ORDER_SERVICE_URL")).isNull();
        }
    }
}
