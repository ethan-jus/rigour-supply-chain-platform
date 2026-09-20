package com.rigour.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 选择 local 时，application-local.yml 在 --- 之后的覆盖必须能盖住
 * 由 spring.config.import 引入的 application-dev.yml 值。
 *
 * <p>这是"本机开发全部走 localhost、dev 保持混合"这一约定的前提；
 * 一旦 Spring 的导入文档顺序语义变化，这里会先失败，而不是等联调时才发现网关把请求发去了台式机。</p>
 */
class GatewayLocalProfileOverrideTest {
    /** 只用来看配置数据装载结果，不启用任何自动配置，也不参与其他测试的上下文搜索。 */
    @Configuration
    static class BareConfiguration {
    }

    @Test
    void localDocumentOverridesImportedDevValues() {
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(BareConfiguration.class)
                        .profiles("local")
                        .web(WebApplicationType.NONE)
                        .run()) {
            var environment = context.getEnvironment();

            // local 里显式覆盖的五个服务：全部落到本机
            assertThat(environment.getProperty("ERP_SERVICE_URL"))
                    .isEqualTo("http://localhost:26884");
            assertThat(environment.getProperty("BUSINESS_SETTINGS_SERVICE_URL"))
                    .isEqualTo("http://localhost:26892");
            assertThat(environment.getProperty("SALES_SERVICE_URL"))
                    .isEqualTo("http://localhost:26886");
            assertThat(environment.getProperty("AI_SERVICE_URL"))
                    .isEqualTo("http://localhost:26887");
            assertThat(environment.getProperty("BI_SERVICE_URL"))
                    .isEqualTo("http://localhost:26888");

            // 未覆盖的服务继续继承 dev，不需要在 local 里重复声明
            assertThat(environment.getProperty("IAM_SERVICE_URL"))
                    .isEqualTo("http://localhost:26881");
            assertThat(environment.getProperty("CRM_SERVICE_URL"))
                    .isEqualTo("http://localhost:26883");
            assertThat(environment.getProperty("ORDER_SERVICE_URL"))
                    .isEqualTo("http://localhost:26885");
            assertThat(environment.getProperty("INTEGRATION_SERVICE_URL"))
                    .isEqualTo("http://localhost:26882");

            // 注册中心仍然继承 dev 的台式机地址，本机不装中间件
            assertThat(environment.getProperty("spring.cloud.nacos.server-addr"))
                    .isEqualTo("192.168.12.7:18848");
            assertThat(environment.getProperty("spring.cloud.nacos.discovery.namespace"))
                    .isEqualTo("3aa03547-8948-4254-bd94-47c630db128b");
            assertThat(environment.getProperty("spring.cloud.nacos.discovery.register-enabled"))
                    .isEqualTo("false");
        }
    }

    @Test
    void devProfileKeepsRoutingErpToDesktop() {
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(BareConfiguration.class)
                        .profiles("dev")
                        .web(WebApplicationType.NONE)
                        .run()) {
            var environment = context.getEnvironment();

            // dev 是"只调本机某个服务"的模式：ERP 仍走台式机，ORDER 走本机
            assertThat(environment.getProperty("ERP_SERVICE_URL"))
                    .isEqualTo("http://192.168.12.7:26884");
            assertThat(environment.getProperty("ORDER_SERVICE_URL"))
                    .isEqualTo("http://localhost:26885");
        }
    }
}
