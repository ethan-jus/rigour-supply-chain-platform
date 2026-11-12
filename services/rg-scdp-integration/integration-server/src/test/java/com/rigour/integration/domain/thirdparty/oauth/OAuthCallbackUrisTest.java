package com.rigour.integration.domain.thirdparty.oauth;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OAuthCallbackUrisTest {
    private static final String PATH = "/api/v1/integration/huolala/oauth/callback";

    @Test
    void developmentAcceptsPrivateAddressButNotArbitraryHttp() {
        for (String host :
                new String[] {"localhost", "127.0.0.1", "192.168.9.90", "10.0.0.2", "172.16.0.1"})
            assertThat(
                            OAuthCallbackUris.require(
                                            "http://" + host + ":26882" + PATH, PATH, true)
                                    .getPath())
                    .isEqualTo(PATH);
        for (String host :
                new String[] {
                    "public.example",
                    "8.8.8.8",
                    "172.32.0.1",
                    "192.169.1.1",
                    "10.1.2.999",
                    "010.1.2.3"
                })
            assertThatThrownBy(() -> OAuthCallbackUris.require("http://" + host + PATH, PATH, true))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionRequiresHttpsEvenForLocalhost() {
        assertThatThrownBy(
                        () ->
                                OAuthCallbackUris.require(
                                        "http://localhost:26882" + PATH, PATH, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                OAuthCallbackUris.require(
                                        "http://192.168.9.90:26882" + PATH, PATH, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OAuthCallbackUris.require("https://api.example" + PATH, PATH, false).getScheme())
                .isEqualTo("https");
    }

    @Test
    void queryUserInfoWrongPathAndInvalidPortsAreRejected() {
        for (String suffix :
                new String[] {PATH + "?tenant=forged", PATH + "#x", "/huolala-authorized.html"})
            assertThatThrownBy(
                            () ->
                                    OAuthCallbackUris.require(
                                            "https://api.example" + suffix, PATH, false))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                OAuthCallbackUris.require(
                                        "https://user@api.example" + PATH, PATH, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                OAuthCallbackUris.require(
                                        "https://api.example:99999" + PATH, PATH, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
