package com.rigour.sales.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.sales.application.port.out.AmapPoiException;
import com.rigour.shared.context.*;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

class HttpIntegrationAmapClientTest {
    HttpServer server;
    HttpIntegrationAmapClient client;
    TrustedContextSigner signer;
    MockHttpServletRequest request;
    String body;
    int status = 200;
    final String tenant = UUID.randomUUID().toString();

    @BeforeEach void start() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        signer = new TrustedContextSigner(trust);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/integration/amap", exchange -> {
            request = new MockHttpServletRequest("GET", exchange.getRequestURI().getRawPath());
            request.setQueryString(exchange.getRequestURI().getRawQuery());
            exchange.getRequestHeaders().forEach((key, values) -> request.addHeader(key, values.getFirst()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new HttpIntegrationAmapClient(RestClient.builder(), signer,
                "http://127.0.0.1:" + server.getAddress().getPort(), tenant);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void nearbyUsesSignedIntegrationContractWithoutProviderKey() {
        body = """
                {"code":"OK","data":{"items":[{"poiId":"B001","name":"球馆","address":"地址",
                "longitude":120.1,"latitude":30.2,"adcode":"330105","publicPhone":"0571-12345678"}],
                "page":1,"pageSize":20,"total":1}}
                """;
        var page = client.searchAround("台球", new BigDecimal("120.1"), new BigDecimal("30.2"), 1000, 1, 20);
        assertThat(page.items()).singleElement().satisfies(p -> {
            assertThat(p.poiId()).isEqualTo("B001");
            assertThat(p.publicPhone()).isEqualTo("0571-12345678");
        });
        assertThat(signer.verify(request)).isTrue();
        assertThat(request.getRequestURI()).endsWith("/pois/around");
        assertThat(request.getHeader(RequestHeaders.PERMISSIONS)).isEqualTo("integration:amap:read");
        assertThat(request.getQueryString()).doesNotContain("key=");
    }

    @Test void reverseGeocodeKeepsProviderFailureAsDataForExistingCaptureFlow() {
        body = """
                {"code":"OK","data":{"status":"KEY_MISSING","errorCode":"AMAP_WEB_KEY_MISSING"}}
                """;
        var result = client.resolve(new BigDecimal("120.1"), new BigDecimal("30.2"));
        assertThat(result.status()).isEqualTo("KEY_MISSING");
        assertThat(result.errorCode()).isEqualTo("AMAP_WEB_KEY_MISSING");
        assertThat(signer.verify(request)).isTrue();
    }

    @Test void providerWhitelistFailureHasSafeActionableReason() {
        status = 503;
        body = """
                {"code":"SERVICE_UNAVAILABLE","message":"private remote diagnostic",
                "details":[{"field":"amap","reason":"AMAP_IP_NOT_ALLOWED","message":"private diagnostic"}]}
                """;
        assertThatThrownBy(() -> client.detail("B001"))
                .isInstanceOfSatisfying(AmapPoiException.class,
                        e -> assertThat(e.reason()).isEqualTo("AMAP_IP_NOT_ALLOWED"))
                .hasMessageNotContaining("private");
    }
}
