package com.rigour.order.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OrderLocalServiceUrlTest {
    @Test void localRoutesKnownContainerHostToLocalServiceAndPreservesPath() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "local");
        assertThat(OrderInfrastructureConfiguration.localServiceUrl(environment,
                "http://rigour-erp-core-service:26884/prefix", "rigour-erp-core-service", 26884))
                .isEqualTo("http://127.0.0.1:26884/prefix");
    }

    @Test void devAndProductionKeepTheirConfiguredHosts() {
        for (String profile : new String[] {"dev", "prod"}) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            String configured = "http://rigour-erp-core-service:26884";
            assertThat(OrderInfrastructureConfiguration.localServiceUrl(environment, configured,
                    "rigour-erp-core-service", 26884)).isEqualTo(configured);
        }
    }

    @Test void localDoesNotReplaceExplicitEndpointsOrUnrelatedServices() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "local");
        for (String configured : new String[] {"http://localhost:28084", "https://erp.example.com",
                "http://rigour-merchant-crm-service:26883", "invalid url"}) {
            assertThat(OrderInfrastructureConfiguration.localServiceUrl(environment, configured,
                    "rigour-erp-core-service", 26884)).isEqualTo(configured);
        }
    }
}
