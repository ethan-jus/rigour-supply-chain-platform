package com.rigour.merchant.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.context.TrustedContextSigner;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/** Exercise the actual HTTP contract: a target lookup must retain the requesting user's identity. */
class HttpCustomerAssignmentTargetClientTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final CallerIdentity caller = new CallerIdentity("TENANT", user, tenant, user, null,
            UUID.randomUUID(), 2, 3, 4, Set.of(), Set.of("crm:customer:assign-owner"));
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<MockHttpServletRequest> request = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private TrustedContextSigner signer;
    private HttpCustomerAssignmentTargetClient client;

    @BeforeEach
    void startServer() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        signer = new TrustedContextSigner(trust);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/iam/supply/customer-assignment-target", exchange -> {
            var req = new MockHttpServletRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
            req.setQueryString(exchange.getRequestURI().getRawQuery());
            exchange.getRequestHeaders().forEach((key, values) -> req.addHeader(key, values.getFirst()));
            request.set(req);
            calls.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new HttpCustomerAssignmentTargetClient(signer, "http://127.0.0.1:" + server.getAddress().getPort());
        body.set(response(tenant, target, "EMP-A"));
        TestAuthorizationContext.set(caller);
    }

    @AfterEach
    void cleanup() {
        TestAuthorizationContext.clear();
        if (server != null) server.stop(0);
    }

    @Test
    void signsOriginalUserSessionAndReadsTargetCeiling() {
        var result = client.byUser(tenant.toString(), target);
        var sent = request.get();
        assertThat(signer.verify(sent)).isTrue();
        assertThat(sent.getHeader(RequestHeaders.PRINCIPAL_SCOPE)).isEqualTo("TENANT");
        assertThat(sent.getHeader(RequestHeaders.USER_ID)).isEqualTo(user.toString());
        assertThat(sent.getHeader(RequestHeaders.TENANT_ID)).isEqualTo(tenant.toString());
        assertThat(sent.getHeader(RequestHeaders.SESSION_ID)).isEqualTo(caller.sessionId().toString());
        assertThat(sent.getHeader(RequestHeaders.SESSION_VERSION)).isEqualTo("2");
        assertThat(sent.getHeader(RequestHeaders.USER_SECURITY_VERSION)).isEqualTo("3");
        assertThat(sent.getHeader(RequestHeaders.TENANT_POLICY_VERSION)).isEqualTo("4");
        assertThat(sent.getHeader(RequestHeaders.PERMISSIONS)).isNull();
        assertThat(sent.getQueryString()).isEqualTo("userId=" + target);
        assertThat(result.userId()).isEqualTo(target);
        assertThat(result.regionLimit().references()).containsExactly("HZ");
    }

    @Test
    void encodesEmployeeLookupAndBindsQueryToSignature() {
        String employee = "EMP 中文 & B";
        body.set(response(tenant, target, employee));
        assertThat(client.byEmployee(tenant.toString(), employee).employeeCode()).isEqualTo(employee);
        var sent = request.get();
        assertThat(signer.verify(sent)).isTrue();
        assertThat(URLDecoder.decode(sent.getQueryString(), StandardCharsets.UTF_8)).isEqualTo("employeeCode=" + employee);
        sent.setQueryString("employeeCode=ANOTHER");
        assertThat(signer.verify(sent)).isFalse();
    }

    @Test
    void rejectsForeignTenantOrMismatchedTargetResponses() {
        body.set(response(UUID.randomUUID(), target, "EMP-A"));
        assertUnavailable(() -> client.byUser(tenant.toString(), target));
        body.set(response(tenant, UUID.randomUUID(), "EMP-A"));
        assertUnavailable(() -> client.byUser(tenant.toString(), target));
        body.set(response(tenant, target, "EMP-OTHER"));
        assertUnavailable(() -> client.byEmployee(tenant.toString(), "EMP-A"));
        body.set("{}");
        assertUnavailable(() -> client.byUser(tenant.toString(), target));
    }

    @Test
    void failsClosedForDeniedOrUnavailableAuthorityAndWrongRequestTenant() {
        status.set(403);
        assertThatThrownBy(() -> client.byUser(tenant.toString(), target)).isInstanceOf(AuthorizationDeniedException.class);
        status.set(503);
        assertUnavailable(() -> client.byUser(tenant.toString(), target));
        int previousCalls = calls.get();
        assertThatThrownBy(() -> client.byUser(UUID.randomUUID().toString(), target)).isInstanceOf(AuthorizationDeniedException.class);
        assertThat(calls.get()).isEqualTo(previousCalls);
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));
    }

    private static String response(UUID tenant, UUID user, String employee) {
        return """
                {"tenantId":"%s","userId":"%s","employeeCode":"%s","employeeName":"张三",
                 "memberStatus":"ACTIVE","usable":true,"unavailableReason":null,
                 "authorizationVersion":7,"regionLimit":{"mode":"SPECIFIED","references":["HZ"]}}
                """.formatted(tenant, user, employee);
    }
}
