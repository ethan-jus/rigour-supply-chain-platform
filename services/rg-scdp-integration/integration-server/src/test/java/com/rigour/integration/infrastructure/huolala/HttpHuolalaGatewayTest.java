package com.rigour.integration.infrastructure.huolala;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.application.port.out.HuolalaGateway.Token;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;

class HttpHuolalaGatewayTest {
    @TempDir Path directory;

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
    void encryptedTokenRoundTripIsPrivateTenantBoundAndNeverPlaintext() throws Exception {
        Path root = directory.resolve("tokens");
        var env =
                Map.of(
                        "RIGOUR_HUOLALA_TOKEN_DIR",
                        root.toString(),
                        "RIGOUR_HUOLALA_TOKEN_KEY",
                        Base64.getEncoder().encodeToString(new byte[32]));
        var gateway =
                new HttpHuolalaGateway(
                        JsonMapper.builder().build(), env::get, HttpClient.newHttpClient());
        String tenant = UUID.randomUUID().toString(), connector = UUID.randomUUID().toString();
        var token =
                new Token(
                        "fixture-access", "fixture-refresh", Instant.parse("2027-01-01T00:00:00Z"));
        gateway.save(tenant, connector, token);
        assertThat(gateway.load(tenant, connector)).isEqualTo(token);
        Path file = root.resolve(tenant + "-" + connector + ".enc");
        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(
                        new String(
                                Files.readAllBytes(file),
                                java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("fixture-access", "fixture-refresh");
        byte[] content = Files.readAllBytes(file);
        content[content.length - 1] ^= 1;
        Files.write(file, content);
        assertThatThrownBy(() -> gateway.load(tenant, connector)).hasMessageContaining("无法读取");
        assertThatThrownBy(() -> gateway.load("../other", connector))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(token.toString()).doesNotContain("fixture-access", "fixture-refresh");
    }

    @Test
    void tokenForAnotherTenantCannotBeReused() throws Exception {
        var env =
                Map.of(
                        "RIGOUR_HUOLALA_TOKEN_DIR",
                        directory.resolve("tokens").toString(),
                        "RIGOUR_HUOLALA_TOKEN_KEY",
                        Base64.getEncoder().encodeToString(new byte[32]));
        var gateway =
                new HttpHuolalaGateway(
                        JsonMapper.builder().build(), env::get, HttpClient.newHttpClient());
        String tenant = UUID.randomUUID().toString(),
                other = UUID.randomUUID().toString(),
                connector = UUID.randomUUID().toString();
        gateway.save(
                tenant,
                connector,
                new Token(
                        "fixture-access",
                        "fixture-refresh",
                        Instant.parse("2027-01-01T00:00:00Z")));
        Path from = directory.resolve("tokens").resolve(tenant + "-" + connector + ".enc"),
                to = directory.resolve("tokens").resolve(other + "-" + connector + ".enc");
        Files.copy(from, to);
        Files.setPosixFilePermissions(to, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> gateway.load(other, connector)).hasMessageContaining("无法读取");
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
                new Token("fixture-access", "fixture-refresh", Instant.now().plusSeconds(3600)),
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
                                        new Token("x", "y", Instant.now()),
                                        "e-order-request",
                                        Map.of()))
                .hasMessageContaining("仅允许");
    }
}
