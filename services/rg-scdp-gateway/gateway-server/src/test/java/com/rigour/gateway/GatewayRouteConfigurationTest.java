package com.rigour.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 服务退出后不得留下可调用路由；领域路由一律通过注册中心的服务名解析实例。 */
class GatewayRouteConfigurationTest {
    @Test
    void removesRetiredServiceRoutesWithoutReplacingExistingDomains() {
        var properties = routes();
        assertThat(properties.values()).contains("tenant-iam", "merchant-crm", "erp-core", "order-center", "business-settings");
        assertThat(properties.values()).doesNotContain("collaboration", "city-operations", "channel-agent");
        assertThat(properties.values()).allSatisfy(value ->
                assertThat(value.toString()).doesNotContain("COLLABORATION_SERVICE_URL", "/api/v1/conversations", "/api/v1/meetings", "CITY_SERVICE_URL", "CHANNEL_SERVICE_URL", "/api/v1/cities", "/api/v1/channels"));
    }

    @Test
    void routesResolveServicesThroughNacosRegistry() {
        var properties = routes();
        var uris = properties.stringPropertyNames().stream()
                .filter(name -> name.endsWith(".uri"))
                .map(properties::getProperty)
                .toList();
        assertThat(uris).isNotEmpty();
        assertThat(uris).allSatisfy(uri -> assertThat(uri).startsWith("lb://"));
    }

    private static Properties routes() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }
}
