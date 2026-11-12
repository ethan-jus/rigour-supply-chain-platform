package com.rigour.integration.infrastructure.thirdparty.huolala.http;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.domain.thirdparty.oauth.OAuthFailure;
import com.rigour.integration.domain.thirdparty.oauth.OAuthToken;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.*;

class HttpHuolalaGatewayTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialAuthEndTimeIsUsedForExchangeAndRefreshAndRetainedUnchanged(boolean refresh)
            throws Exception {
        var gateway =
                tokenGateway(
                        """
                        {"ret":0,"msg":"success","data":{
                          "access_token":" raw-access+ / ","refresh_token":" raw-refresh+ / ",
                          "auth_end_time":"2099-02-07 15:35:08"}}
                        """);
        var token =
                refresh
                        ? gateway.refresh(
                                connection(),
                                new OAuthToken(
                                        "previous",
                                        "previous-refresh",
                                        Instant.now().plusSeconds(60)))
                        : gateway.exchange(connection(), "fixture-code");
        assertThat(token.accessToken()).isEqualTo(" raw-access+ / ");
        assertThat(token.refreshToken()).isEqualTo(" raw-refresh+ / ");
        assertThat(token.expiresAt()).isEqualTo(Instant.parse("2099-02-07T07:35:08Z"));
        assertThat(token.metadata()).containsEntry("auth_end_time", "2099-02-07 15:35:08");
        assertThat(token.receivedAt()).isNotNull();
        assertThat(token.refreshExpiresAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2099-02-30 15:35:08", "not-a-date"})
    void malformedOfficialExpiryIsRejectedWithoutLeakingResponse(String expiry) throws Exception {
        var gateway =
                tokenGateway(
                        "{\"ret\":0,\"data\":{\"access_token\":\"sensitive-access\",\"refresh_token\":\"sensitive-refresh\",\"auth_end_time\":\""
                                + expiry
                                + "\",\"expires_in\":3600}}");
        assertThatThrownBy(() -> gateway.exchange(connection(), "sensitive-code"))
                .isInstanceOfSatisfying(
                        OAuthFailure.class,
                        f -> assertThat(f.code()).isEqualTo("TOKEN_EXPIRY_INVALID"))
                .hasMessageNotContaining("sensitive-")
                .hasMessageNotContaining(expiry);
    }

    @Test
    void expiredOfficialTokenIsNotAccepted() throws Exception {
        var gateway =
                tokenGateway(
                        "{\"ret\":0,\"data\":{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"auth_end_time\":\"2020-06-07"
                            + " 15:35:08\"}}");
        assertThatThrownBy(() -> gateway.exchange(connection(), "code"))
                .isInstanceOfSatisfying(
                        OAuthFailure.class, f -> assertThat(f.code()).isEqualTo("TOKEN_EXPIRED"));
    }

    @Test
    void platformRejectionKeepsOnlySafeResponseCode() throws Exception {
        var gateway =
                tokenGateway("{\"ret\":17110,\"msg\":\"sensitive-code access-token secret\"}");
        assertThatThrownBy(() -> gateway.exchange(connection(), "sensitive-code"))
                .isInstanceOfSatisfying(
                        OAuthFailure.class,
                        f -> assertThat(f.code()).isEqualTo("TOKEN_REJECTED_17110"))
                .hasMessageNotContaining("sensitive-code")
                .hasMessageNotContaining("access-token")
                .hasMessageNotContaining("secret");
    }

    private HttpHuolalaGateway tokenGateway(String responseJson) throws Exception {
        var http = org.mockito.Mockito.mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        var response =
                (java.net.http.HttpResponse<java.io.InputStream>)
                        org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.body())
                .thenAnswer(
                        inv ->
                                new java.io.ByteArrayInputStream(
                                        responseJson.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(
                        http.send(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers
                                        .<java.net.http.HttpResponse.BodyHandler<
                                                        java.io.InputStream>>
                                                any()))
                .thenReturn(response);
        return new HttpHuolalaGateway(
                JsonMapper.builder().build(),
                Map.of("RIGOUR_HUOLALA_APP_KEY", "fixture-app-key")::get,
                http);
    }

    @Test
    void signatureSortsNamesAndDoesNotUrlEncodeBusinessJsonOrSignSignatureField() throws Exception {
        var p = new LinkedHashMap<String, String>();
        p.put("b", "中文");
        p.put("signature", "ignored");
        p.put("a", "{\"x\":1}");
        p.put("empty", "");
        var expected =
                java.security.MessageDigest.getInstance("MD5")
                        .digest(
                                "a={\"x\":1}&b=中文secret"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(HttpHuolalaGateway.signature(p, "secret"))
                .isEqualTo(HexFormat.of().formatHex(expected));
    }

    @Test
    void nacosPlatformSpecificCredentialsAreReadWithoutTokenFiles() {
        var env =
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("rigour.thirdparty.huolala.app-key", "fixture-nacos-key")
                        .withProperty(
                                "rigour.thirdparty.huolala.app-secret", "fixture-nacos-secret");
        var gateway = new HttpHuolalaGateway(JsonMapper.builder().build(), env);
        var c = connection();
        assertThat(gateway.ready(c)).isTrue();
        assertThat(gateway.appKey(c)).isEqualTo("fixture-nacos-key");
        assertThat(gateway.authorizeUrl(c))
                .contains("client_id=fixture-nacos-key")
                .doesNotContain("fixture-nacos-secret");
    }

    private com.rigour.integration.api.v1.model.HuolalaModels.Connection connection() {
        return new com.rigour.integration.api.v1.model.HuolalaModels.Connection(
                UUID.randomUUID().toString(),
                "123",
                "示例企业",
                "env://RIGOUR_HUOLALA",
                false,
                "http://localhost:26882/api/v1/integration/huolala/oauth/callback",
                false,
                15,
                java.time.LocalDate.now(),
                "UNAUTHORIZED",
                null,
                false,
                1,
                null,
                null);
    }

    @Test
    void officialEnvelopeUsesStringApiDataAndSignedReadonlyMethod() throws Exception {
        var json = JsonMapper.builder().build();
        var http = org.mockito.Mockito.mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        var response =
                (java.net.http.HttpResponse<java.io.InputStream>)
                        org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.body())
                .thenReturn(
                        new java.io.ByteArrayInputStream(
                                "{\"ret\":0,\"data\":{\"order_arr\":[]}}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(
                        http.send(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers
                                        .<java.net.http.HttpResponse.BodyHandler<
                                                        java.io.InputStream>>
                                                any()))
                .thenReturn(response);
        var gateway =
                new HttpHuolalaGateway(
                        json,
                        Map.of("HLL_APP_KEY", "fixture-key", "HLL_APP_SECRET", "fixture-secret")
                                ::get,
                        http);
        var c =
                new com.rigour.integration.api.v1.model.HuolalaModels.Connection(
                        UUID.randomUUID().toString(),
                        "1001",
                        "企业",
                        "env://HLL",
                        false,
                        "https://example.com/huolala-authorized.html",
                        false,
                        15,
                        java.time.LocalDate.now(),
                        "AUTHORIZED",
                        null,
                        true,
                        1,
                        null,
                        null);
        gateway.read(
                c,
                new OAuthToken(
                        "fixture-access", "fixture-refresh", Instant.now().plusSeconds(3600)),
                "e-order-list",
                Map.of("page_size", 20, "order_display_id", 3624071813921329726L));
        var request = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        org.mockito.Mockito.verify(http)
                .send(
                        request.capture(),
                        org.mockito.ArgumentMatchers
                                .<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>
                                        any());
        var body = new java.util.concurrent.CompletableFuture<byte[]>();
        var output = new java.io.ByteArrayOutputStream();
        request.getValue()
                .bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                s.request(Long.MAX_VALUE);
                            }

                            public void onNext(java.nio.ByteBuffer b) {
                                byte[] bytes = new byte[b.remaining()];
                                b.get(bytes);
                                output.writeBytes(bytes);
                            }

                            public void onError(Throwable t) {
                                body.completeExceptionally(t);
                            }

                            public void onComplete() {
                                body.complete(output.toByteArray());
                            }
                        });
        var envelope = json.readTree(body.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://openapi.huolala.cn/v1");
        assertThat(envelope.path("api_data").isString()).isTrue();
        assertThat(
                        json.readTree(envelope.path("api_data").asText())
                                .path("order_display_id")
                                .asText())
                .isEqualTo("3624071813921329726");
        assertThatThrownBy(
                        () ->
                                gateway.read(
                                        c,
                                        new OAuthToken("x", "y", Instant.now()),
                                        "e-order-request",
                                        Map.of()))
                .hasMessageContaining("仅允许");
    }

    @Test
    void tokenResponsePreservesRawValuesAndExpiryMetadata() throws Exception {
        var http = org.mockito.Mockito.mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        var response =
                (java.net.http.HttpResponse<java.io.InputStream>)
                        org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.body())
                .thenReturn(
                        new java.io.ByteArrayInputStream(
                                JsonMapper.builder()
                                        .build()
                                        .createObjectNode()
                                        .put("access_token", " raw-access+ / ")
                                        .put("refresh_token", " raw-refresh+ / ")
                                        .put("expires_in", 3600)
                                        .put("token_type", "bearer")
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(
                        http.send(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers
                                        .<java.net.http.HttpResponse.BodyHandler<
                                                        java.io.InputStream>>
                                                any()))
                .thenReturn(response);
        var gateway =
                new HttpHuolalaGateway(
                        JsonMapper.builder().build(),
                        Map.of(
                                        "RIGOUR_HUOLALA_APP_KEY",
                                        "fixture",
                                        "RIGOUR_HUOLALA_APP_SECRET",
                                        "fixture-secret")
                                ::get,
                        http);
        var token = gateway.exchange(connection(), "code");
        assertThat(token.accessToken()).isEqualTo(" raw-access+ / ");
        assertThat(token.refreshToken()).isEqualTo(" raw-refresh+ / ");
        assertThat(token.metadata()).containsEntry("expires_in", 3600);
        assertThat(token.refreshExpiresAt()).isNull();
        assertThat(token.toString()).doesNotContain("raw-access", "raw-refresh");
    }

    @Test
    void ambiguousTokenNetworkFailureIsNotRetried() throws Exception {
        var http = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(
                        http.send(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers
                                        .<java.net.http.HttpResponse.BodyHandler<
                                                        java.io.InputStream>>
                                                any()))
                .thenThrow(new java.io.IOException("credential-must-not-leak"));
        var gateway =
                new HttpHuolalaGateway(
                        JsonMapper.builder().build(),
                        Map.of("RIGOUR_HUOLALA_APP_KEY", "fixture")::get,
                        http);
        assertThatThrownBy(() -> gateway.exchange(connection(), "sensitive-code"))
                .isInstanceOf(com.rigour.integration.domain.thirdparty.oauth.OAuthFailure.class)
                .hasMessageNotContaining("sensitive-code")
                .hasMessageNotContaining("credential-must-not-leak");
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(1))
                .send(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers
                                .<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>
                                        any());
    }
}
