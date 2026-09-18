package com.rigour.analytics.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 真实 HTTP 响应中的金额与大整数精度，以及跨服务签名契约。 */
class HttpBiSourceSnapshotClientTest {
    @Test
    void keepsExactTextAndRejectsNumericSourceFields() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var signer = new TrustedContextSigner(trust);
        UUID tenant = UUID.randomUUID();
        var numeric = new AtomicBoolean();
        var invalid = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/orders/analytics-source/ORDER_PAYMENT_RECORD",
                exchange -> {
                    var req =
                            new MockHttpServletRequest(
                                    "GET", exchange.getRequestURI().getRawPath());
                    req.setQueryString(exchange.getRequestURI().getRawQuery());
                    exchange.getRequestHeaders().forEach((k, v) -> req.addHeader(k, v.getFirst()));
                    boolean valid =
                            signer.verify(req)
                                    && tenant.toString()
                                            .equals(req.getHeader(RequestHeaders.TENANT_ID))
                                    && "order:analytics:source-read"
                                            .equals(req.getHeader(RequestHeaders.PERMISSIONS));
                    if (!valid) invalid.incrementAndGet();
                    String body =
                            exchange.getRequestURI().getPath().endsWith("/version")
                                    ? "{\"version\":\"v1\"}"
                                    : "{\"code\":\"OK\",\"data\":{\"version\":\"v1\",\"items\":[{\"id\":"
                                            + (numeric.get()
                                                    ? "9007199254740993"
                                                    : "\"9007199254740993\"")
                                            + ",\"amount\":\"123456789012.123456\"}]}}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(valid ? 200 : 403, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new HttpBiSourceSnapshotClient(signer, base, base, base, base, base, base);
            assertThat(client.version(tenant, "ORDER", "ORDER_PAYMENT_RECORD")).isEqualTo("v1");
            var page = client.page(tenant, "ORDER", "ORDER_PAYMENT_RECORD", "");
            assertThat(page.items().getFirst())
                    .containsEntry("id", "9007199254740993")
                    .containsEntry("amount", "123456789012.123456");
            assertThat(invalid.get()).isZero();
            numeric.set(true);
            assertThatThrownBy(
                            () ->
                                    client.page(
                                            tenant,
                                            "ORDER",
                                            "ORDER_PAYMENT_RECORD",
                                            "9007199254740993"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("精确文本");
        } finally {
            server.stop(0);
        }
    }
}
