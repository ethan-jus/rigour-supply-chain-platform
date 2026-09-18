package com.rigour.gateway.security;

import com.rigour.gateway.config.GatewaySecurityProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityPropertiesTest {
    @Test
    void allowsDesktopHttpOnlyWhenExplicitlyEnabled() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setIssuer("http://192.168.12.7:26881");
        assertThatThrownBy(properties::requireIssuer).isInstanceOf(IllegalStateException.class);
        properties.setAllowInsecureHttp(true);
        assertThat(properties.requireIssuer()).isEqualTo("http://192.168.12.7:26881");
        properties.setIssuer("http://user:pass@192.168.12.7:26881");
        assertThatThrownBy(properties::requireIssuer).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void requiresOnlineIamValidationWhenGatewaySecurityIsEnabled() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setIamCurrentTokenUri("https://iam.example/api/v1/token/current");
        assertThatThrownBy(properties::requireCurrentTokenValidation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires IAM current-token validation");
    }

    @Test
    void rejectsUnboundedCurrentTokenTimeouts() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setCurrentTokenValidationEnabled(true);
        properties.setIamCurrentTokenUri("https://iam.example/api/v1/token/current");
        properties.setCurrentTokenReadTimeout(Duration.ofMinutes(1));
        assertThatThrownBy(properties::requireCurrentTokenValidation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current-token-read-timeout");
    }
}
