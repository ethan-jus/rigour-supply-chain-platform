package com.rigour.tenant.iam.client;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 跨服务真实 HTTP 请求保留原会话签名；观察写入不替代正式读取。 */
class HttpSupplyAuthorizationClientTest {
    @Test
    void signsReadsAndObservationPostsWithTheOriginalUser() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var signer = new TrustedContextSigner(trust);
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
        var caller =
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        2,
                        3,
                        Set.of(),
                        Set.of());
        var calls = new AtomicInteger();
        var invalid = new AtomicInteger();
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/iam/supply",
                exchange -> {
                    var req =
                            new MockHttpServletRequest(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().getRawPath());
                    req.setQueryString(exchange.getRequestURI().getRawQuery());
                    exchange.getRequestHeaders().forEach((k, v) -> req.addHeader(k, v.getFirst()));
                    boolean valid =
                            signer.verify(req)
                                    && user.toString().equals(req.getHeader(RequestHeaders.USER_ID))
                                    && tenant.toString()
                                            .equals(req.getHeader(RequestHeaders.TENANT_ID))
                                    && caller.sessionId()
                                            .toString()
                                            .equals(req.getHeader(RequestHeaders.SESSION_ID));
                    if (!valid) invalid.incrementAndGet();
                    calls.incrementAndGet();
                    if (exchange.getRequestMethod().equals("POST")) {
                        body.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        exchange.sendResponseHeaders(valid ? 204 : 403, -1);
                    } else {
                        String response =
                                "{\"mode\":\"PREPARING\",\"tenantId\":\""
                                        + tenant
                                        + "\",\"userId\":\""
                                        + user
                                        + "\",\"employeeCode\":null,\"applicationVersion\":9,\"memberVersion\":0,\"employeeRevision\":0,\"organizationVersion\":0,\"permissions\":[],\"action\":\"order:read\",\"functionAllowed\":false,\"clauses\":[],\"regionLimit\":{\"mode\":\"NONE\",\"references\":[]},\"warehouseLimit\":{\"mode\":\"NONE\",\"references\":[]}}";
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(valid ? 200 : 403, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                    exchange.close();
                });
        server.start();
        try {
            var client =
                    new HttpSupplyAuthorizationClient(
                            signer,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            org.springframework.web.client.RestClient.builder());
            var result = client.authorization(caller, "order:read");
            assertThat(result.mode()).isEqualTo("PREPARING");
            client.observe(caller, "order:create", "order:write");
            assertThat(calls.get()).isEqualTo(2);
            assertThat(invalid.get()).isZero();
            assertThat(body.get())
                    .contains("\"action\":\"order:create\"", "\"legacyAction\":\"order:write\"");
            assertThat(client.candidate(caller, "order:read").applicationVersion()).isEqualTo(9);
            client.observeData(
                    caller,
                    new com.rigour.tenant.iam.api.v1.model.SupplyDataObservation(
                            "order:read", "ORDER", "123", 9, 0, 0, 0, true, false));
            assertThat(calls.get()).isEqualTo(4);
            assertThat(invalid.get()).isZero();
            assertThat(body.get()).contains("\"recordKey\":\"123\"", "\"proposedAllowed\":false");

        } finally {
            server.stop(0);
        }
    }
}
