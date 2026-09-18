package com.rigour.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** 服务退出后不得留下可调用路由，现有领域路由继续保留。 */
class GatewayRouteConfigurationTest {
    @Test
    void removesRetiredServiceRoutesWithoutReplacingExistingDomains() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.values()).contains("tenant-iam", "merchant-crm", "erp-core", "order-center", "business-settings");
        assertThat(properties.values()).doesNotContain("collaboration", "city-operations", "channel-agent");
        assertThat(properties.values()).allSatisfy(value ->
                assertThat(value.toString()).doesNotContain("COLLABORATION_SERVICE_URL", "/api/v1/conversations", "/api/v1/meetings", "CITY_SERVICE_URL", "CHANNEL_SERVICE_URL", "/api/v1/cities", "/api/v1/channels"));
    }
}
