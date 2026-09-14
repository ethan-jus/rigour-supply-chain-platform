package com.rigour.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 协作服务部署后必须有真实 Gateway HTTP 路由，不能仅以进程健康作为可访问证据。 */
class GatewayRouteConfigurationTest {
    @Test
    void routesCollaborationContractsWithoutReplacingExistingDomains() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        String prefix = properties.stringPropertyNames().stream()
                .filter(key -> key.endsWith(".id") && "collaboration".equals(properties.getProperty(key)))
                .findFirst().orElseThrow().replaceFirst("\\.id$", "");
        assertThat(properties.getProperty(prefix + ".uri"))
                .isEqualTo("${COLLABORATION_SERVICE_URL:http://localhost:26893}");
        assertThat(properties.getProperty(prefix + ".predicates[0]"))
                .contains("/api/v1/collaboration/**", "/api/v1/conversations/**", "/api/v1/messages/**",
                        "/api/v1/attachments/**", "/api/v1/meetings/**", "/api/v1/devices");
        assertThat(properties.values()).contains("tenant-iam", "merchant-crm", "erp-core", "order-center", "business-settings");
    }
}
