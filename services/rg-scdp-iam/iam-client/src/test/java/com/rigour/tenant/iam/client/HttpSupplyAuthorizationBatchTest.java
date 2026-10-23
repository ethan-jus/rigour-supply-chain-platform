package com.rigour.tenant.iam.client;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

class HttpSupplyAuthorizationBatchTest {
    @Test
    void signsAllActionsAndOriginalSubjectAndNeverFallsBackOnBadResponses() throws Exception {
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
        var limit = new SupplyAuthorizationView.Limit("ALL", List.of());
        var permissions = Set.of("order:read");
        var allowed =
                new SupplyAuthorizationView(
                        "ACTIVE",
                        tenant,
                        user,
                        "E1",
                        1,
                        2,
                        3,
                        4,
                        permissions,
                        "order:read",
                        true,
                        List.of(),
                        limit,
                        limit);
        var denied =
                new SupplyAuthorizationView(
                        "ACTIVE",
                        tenant,
                        user,
                        "E1",
                        1,
                        2,
                        3,
                        4,
                        permissions,
                        "hr:payroll:read",
                        false,
                        List.of(),
                        limit,
                        limit);
        var json = JsonMapper.builder().build();
        var response = new AtomicReference<List<SupplyAuthorizationView>>(List.of(allowed, denied));
        var status = new AtomicInteger(200);
        var calls = new AtomicInteger();
        var valid = new AtomicInteger();
        var queries = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/iam/supply/authorizations",
                exchange -> {
                    var request =
                            new MockHttpServletRequest(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().getRawPath());
                    request.setQueryString(exchange.getRequestURI().getRawQuery());
                    exchange.getRequestHeaders()
                            .forEach((key, values) -> request.addHeader(key, values.getFirst()));
                    if (signer.verify(request)
                            && user.toString().equals(request.getHeader(RequestHeaders.USER_ID))
                            && tenant.toString().equals(request.getHeader(RequestHeaders.TENANT_ID))
                            && caller.sessionId()
                                    .toString()
                                    .equals(request.getHeader(RequestHeaders.SESSION_ID)))
                        valid.incrementAndGet();
                    calls.incrementAndGet();
                    queries.set(
                            URLDecoder.decode(
                                    exchange.getRequestURI().getRawQuery(),
                                    StandardCharsets.UTF_8));
                    byte[] bytes =
                            json.writeValueAsString(response.get())
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var client =
                    new HttpSupplyAuthorizationClient(
                            signer,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            RestClient.builder(),
                            Duration.ofSeconds(2));
            assertThat(client.authorizations(caller, List.of("order:read", "hr:payroll:read")))
                    .containsExactly(allowed, denied);
            assertThat(queries.get()).isEqualTo("actions=order:read&actions=hr:payroll:read");
            response.set(List.of(allowed));
            assertThatThrownBy(
                            () ->
                                    client.authorizations(
                                            caller, List.of("order:read", "hr:payroll:read")))
                    .isInstanceOf(IllegalStateException.class);
            status.set(503);
            assertThatThrownBy(
                            () ->
                                    client.authorizations(
                                            caller, List.of("order:read", "hr:payroll:read")))
                    .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
            assertThat(calls).hasValue(3);
            assertThat(valid).hasValue(3);
        } finally {
            server.stop(0);
        }
    }
}
