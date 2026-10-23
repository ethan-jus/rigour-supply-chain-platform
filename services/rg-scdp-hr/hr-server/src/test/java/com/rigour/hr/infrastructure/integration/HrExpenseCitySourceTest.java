package com.rigour.hr.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class HrExpenseCitySourceTest {
    HttpServer server;
    HttpHrExpenseCitySource source;
    final UUID tenant = UUID.randomUUID(), operator = UUID.randomUUID();
    final AtomicReference<Map<String, String>> request = new AtomicReference<>();
    int status = 200;
    String body =
            """
            {"code":"OK","data":[{"code":"420100","name":"武汉市"}]}
            """;

    @BeforeEach
    void setup() throws Exception {
        var props = new ContextTrustProperties();
        props.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        operator,
                        tenant,
                        operator,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of(),
                        Set.of("hr:expense:write")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/crm/cost-city-references",
                exchange -> {
                    request.set(
                            Map.of(
                                    "query",
                                    exchange.getRequestURI().getRawQuery(),
                                    "tenant",
                                    exchange.getRequestHeaders().getFirst(RequestHeaders.TENANT_ID),
                                    "principal",
                                    exchange.getRequestHeaders()
                                            .getFirst(RequestHeaders.PRINCIPAL_ID),
                                    "scope",
                                    exchange.getRequestHeaders()
                                            .getFirst(RequestHeaders.PRINCIPAL_SCOPE),
                                    "signature",
                                    exchange.getRequestHeaders()
                                            .getFirst(RequestHeaders.CONTEXT_SIGNATURE)));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                });
        server.start();
        source =
                new HttpHrExpenseCitySource(
                        RestClient.builder(),
                        new TrustedContextSigner(props),
                        "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void clear() {
        if (server != null) server.stop(0);
        TestAuthorizationContext.clear();
    }

    @Test
    void forwardsSignedTenantCallerAndExactActionToExistingDirectory() {
        assertThat(source.cityName("420100", "hr:expense:write")).isEqualTo("武汉市");
        assertThat(request.get())
                .containsEntry("tenant", tenant.toString())
                .containsEntry("principal", operator.toString())
                .containsEntry("scope", "TENANT");
        assertThat(request.get().get("query")).isEqualTo("action=hr:expense:write");
        assertThat(request.get().get("signature")).isNotBlank();
    }

    @Test
    void absentCityNeverFallsBackToUnscopedDirectory() {
        assertThatThrownBy(() -> source.cityName("110100", "hr:expense:write"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void upstreamDenialPreservesAuthorizationFailure() {
        status = 403;
        body = "{\"code\":\"FORBIDDEN\"}";
        assertThatThrownBy(() -> source.cityName("420100", "hr:expense:write"))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void upstreamOutageHasActionableRetryMessageAndNeverAllowsWrite() {
        status = 503;
        body = "{\"code\":\"SERVICE_UNAVAILABLE\"}";
        assertThatThrownBy(() -> source.cityName("420100", "hr:expense:write"))
                .hasMessageContaining("授权城市目录暂不可用，请稍后重试");
    }
}
