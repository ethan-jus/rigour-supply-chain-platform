package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

class HttpEmployeePerformanceTargetClientTest {
    @Test
    void signsTenantScopedReadAndDoesNotFallbackOnFailure() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var signer = new TrustedContextSigner(trust);
        var tenant = UUID.randomUUID();
        var fail = new AtomicBoolean();
        var valid = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/hr/target-settings/performance-value",
                exchange -> {
                    var request =
                            new MockHttpServletRequest(
                                    "GET", exchange.getRequestURI().getRawPath());
                    request.setQueryString(exchange.getRequestURI().getRawQuery());
                    exchange.getRequestHeaders()
                            .forEach((k, v) -> request.addHeader(k, v.getFirst()));
                    valid.set(
                            signer.verify(request)
                                    && "employeeCode=E1&from=2026-10&to=2026-10".equals(request.getQueryString())
                                    && tenant.toString()
                                            .equals(request.getHeader(RequestHeaders.TENANT_ID))
                                    && "SERVICE"
                                            .equals(
                                                    request.getHeader(
                                                            RequestHeaders.PRINCIPAL_SCOPE))
                                    && "hr:targets:service-read"
                                            .equals(request.getHeader(RequestHeaders.PERMISSIONS)));
                    byte[] body =
                            """
{"code":"OK","data":123456789012.12}
"""
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(fail.get() ? 503 : 200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var client =
                    new HttpEmployeePerformanceTargetClient(
                            signer,
                            RestClient.builder(),
                            "http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(client.value(tenant.toString(), "E1", "2026-10", "2026-10"))
                    .isEqualByComparingTo("123456789012.12");
            assertThat(valid).isTrue();
            fail.set(true);
            assertThatThrownBy(() -> client.value(tenant.toString(), "E1", "2026-10", "2026-10"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("人事指标暂时无法读取");
        } finally {
            server.stop(0);
        }
    }
}
