package com.rigour.tenant.iam;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class IamSessionConfigurationTest {

    @Test
    void servletSsoSessionMatchesDatabaseAuthenticationSessionTtl() throws Exception {
        var environment = new StandardEnvironment();
        var application = new YamlPropertySourceLoader().load(
                "iam-application", new ClassPathResource("application.yml"));
        application.reversed().forEach(environment.getPropertySources()::addFirst);
        Binder binder = Binder.get(environment);

        Duration servletSession = binder.bind(
                "server.servlet.session.timeout", Duration.class)
                .orElseThrow(() -> new AssertionError("server.servlet.session.timeout is required"));
        Duration databaseSession = binder.bind(
                "rigour.iam.authentication.password.session-time-to-live", Duration.class)
                .orElseThrow(() -> new AssertionError("IAM database session TTL is required"));

        assertThat(servletSession).isEqualTo(Duration.ofHours(8));
        assertThat(servletSession).isEqualTo(databaseSession);
    }
}
