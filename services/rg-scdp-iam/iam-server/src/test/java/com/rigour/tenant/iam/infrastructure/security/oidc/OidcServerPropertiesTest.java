package com.rigour.tenant.iam.infrastructure.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.cors.CorsConfiguration;

class OidcServerPropertiesTest {

    private static final String HTTP_ORIGIN = "http://203.0.113.10:5100";

    @Test
    void rejectsPublicHttpOriginByDefault() {
        OidcServerProperties properties = new OidcServerProperties();
        properties.setAllowedOrigins(List.of(HTTP_ORIGIN));

        assertThatThrownBy(properties::requireAllowedOrigins)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void explicitHttpOverrideAllowsOnlyTheConfiguredOrigin() {
        OidcServerProperties properties = new OidcServerProperties();
        properties.setAllowedOrigins(List.of(HTTP_ORIGIN));
        properties.setAllowInsecureHttpOrigins(true);
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowCredentials(true);
        cors.setAllowedOriginPatterns(properties.requireAllowedOriginPatterns());

        assertThat(cors.checkOrigin(HTTP_ORIGIN)).isEqualTo(HTTP_ORIGIN);
        assertThat(cors.checkOrigin("http://203.0.113.11:5100")).isNull();
        assertThat(cors.checkOrigin("http://203.0.113.10:5101")).isNull();
        assertThat(cors.checkOrigin("http://192.168.1.10:5100")).isNull();
        assertThat(properties.requirePrimaryScdpEntryUri()).isEqualTo(HTTP_ORIGIN + "/");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://*.example.test:5100", "http://203.0.113.10:*",
            "http://user:password@203.0.113.10:5100", "http://203.0.113.10:5100/path",
            "http://203.0.113.10:5100/", "http://203.0.113.10:5100?query=value",
            "http://203.0.113.10:5100#fragment", "ftp://203.0.113.10:5100"
    })
    void httpOverrideStillRejectsNonOriginValues(String value) {
        OidcServerProperties properties = new OidcServerProperties();
        properties.setAllowInsecureHttpOrigins(true);
        properties.setAllowedOrigins(List.of(value));

        assertThatThrownBy(properties::requireAllowedOrigins)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void originOverrideDoesNotPermitAPublicHttpIssuer() {
        OidcServerProperties properties = new OidcServerProperties();
        properties.setAllowInsecureHttpOrigins(true);
        properties.setIssuer("http://203.0.113.10:26881");

        assertThatThrownBy(properties::requireIssuer)
                .isInstanceOf(IllegalStateException.class);
        properties.setIssuer("http://127.0.0.1:26881");
        properties.setAllowInsecureLoopback(true);
        assertThat(properties.requireIssuer()).isEqualTo("http://127.0.0.1:26881");
    }

    @Test
    void httpsOriginRemainsAllowedWithoutHttpOverride() {
        OidcServerProperties properties = new OidcServerProperties();
        properties.setAllowedOrigins(List.of("https://portal.example.test"));

        assertThat(properties.requireAllowedOrigins()).containsExactly("https://portal.example.test");
    }
}
