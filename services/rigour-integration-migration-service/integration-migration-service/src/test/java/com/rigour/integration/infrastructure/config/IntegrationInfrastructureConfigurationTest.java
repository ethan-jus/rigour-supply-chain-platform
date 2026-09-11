package com.rigour.integration.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationInfrastructureConfigurationTest {

    @Test
    void localProfileRoutesServiceNameBaseUrlToLoopback() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "dev,local");

        String baseUrl = IntegrationInfrastructureConfiguration.localLoopbackUrl(
                environment, "http://rigour-business-settings-service:26892", 26892);

        assertThat(baseUrl).isEqualTo("http://127.0.0.1:26892");
    }

    @Test
    void keepsExplicitHostForLocalProfile() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "dev,local");

        String baseUrl = IntegrationInfrastructureConfiguration.localLoopbackUrl(
                environment, "http://192.168.1.10:26892", 26892);

        assertThat(baseUrl).isEqualTo("http://192.168.1.10:26892");
    }

    @Test
    void keepsServiceNameForNonLocalProfile() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "dev");

        String baseUrl = IntegrationInfrastructureConfiguration.localLoopbackUrl(
                environment, "http://rigour-business-settings-service:26892", 26892);

        assertThat(baseUrl).isEqualTo("http://rigour-business-settings-service:26892");
    }
}
