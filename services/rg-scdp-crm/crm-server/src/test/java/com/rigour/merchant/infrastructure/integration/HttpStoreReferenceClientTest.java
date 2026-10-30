package com.rigour.merchant.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.exception.StateConflictException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

class HttpStoreReferenceClientTest {
    private HttpServer server;
    private HttpStoreReferenceClient client;
    private int status;
    private String body;
    private MockHttpServletRequest request;
    private TrustedContextSigner signer;

    @BeforeEach
    void start() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        signer = new TrustedContextSigner(trust);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/", exchange -> {
            request = new MockHttpServletRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
            exchange.getRequestHeaders().forEach((key, values) -> request.addHeader(key, values.getFirst()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new HttpStoreReferenceClient(RestClient.builder(), signer, base, base);
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, UUID.randomUUID(), user, null,
                UUID.randomUUID(), 1, 1, 1, Set.of(), Set.of("crm:pool:public-read")));
    }

    @AfterEach
    void stop() {
        TestAuthorizationContext.clear();
        if (server != null) server.stop(0);
    }

    @Test
    void unpublishedCatalogIsActionableConflictRatherThanInternalError() {
        status = 409;
        body = "{\"code\":\"CONFLICT\",\"message\":\"remote diagnostic\"}";
        assertThatThrownBy(client::divisions).isInstanceOf(StateConflictException.class)
                .hasMessage("行政区划尚未发布，请先导入并核验行政区划数据");
        assertThat(signer.verify(request)).isTrue();
        assertThat(request.getHeader(RequestHeaders.PERMISSIONS)).isEqualTo("settings:admin-division:read");
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500, 503})
    void unavailableCatalogFailsClosedWithoutLeakingRemoteDiagnostic(int responseStatus) {
        status = responseStatus;
        body = "private downstream diagnostic";
        assertThatThrownBy(client::divisions).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessage("行政区划服务暂不可用，请稍后重试");
    }

    @Test
    void malformedSuccessDoesNotBecomeAnEmptyOrNationalCatalog() {
        status = 200;
        body = "{\"code\":\"OK\",\"data\":null}";
        assertThatThrownBy(client::divisions).isInstanceOf(BusinessException.class)
                .hasMessage("行政区划目录响应异常，请稍后重试");
    }

    @Test
    void deniedStoreActionRemainsForbiddenRatherThanAnInternalError() {
        status = 403;
        body = "private downstream diagnostic";
        assertThatThrownBy(() -> client.access("crm:store:manage"))
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessageNotContaining(body);
        assertThat(request.getRequestURI()).isEqualTo("/internal/v1/iam/supply/store-access");
    }
}
