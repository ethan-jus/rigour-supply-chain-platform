package com.rigour.merchant.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.exception.StateConflictException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

class HttpStoreVisitPolicyStoreTest {
    HttpServer server;
    HttpStoreVisitPolicyStore store;
    TrustedContextSigner signer;
    MockHttpServletRequest request;
    StoreScope scope;
    int status;
    String body;

    @BeforeEach void setup() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        signer = new TrustedContextSigner(trust);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/sales/me/visit-policy", exchange -> {
            request = new MockHttpServletRequest("GET", exchange.getRequestURI().getRawPath());
            exchange.getRequestHeaders().forEach((key, values) -> request.addHeader(key, values.getFirst()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        store = new HttpStoreVisitPolicyStore(RestClient.builder(), signer, "http://127.0.0.1:" + server.getAddress().getPort());
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        var setter = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        setter.setAccessible(true);
        setter.invoke(null, new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(), 1, 1, 1, Set.of(), Set.of("sales:context:read")));
        scope = mock(StoreScope.class, RETURNS_DEEP_STUBS);
        when(scope.access().tenantId()).thenReturn(tenant.toString());
    }

    @AfterEach void clear() throws Exception {
        server.stop(0);
        var clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    @Test void readsSalesSnapshotWithSignedCallerAndCaptureRules() {
        status = 200;
        body = """
            {"code":"OK","data":{"id":"00000000-0000-0000-0000-000000000001","radiusMeters":500,
            "minimumDwellMinutes":10,"requiredPhotoCount":1,"recordingEnabled":true,
            "minimumRecordingSeconds":600,"maximumClipGapSeconds":30,"aiAsrEnabled":true,
            "aiRelevanceEnabled":true,"aiDuplicateEnabled":true,"aiAutoConfirmThreshold":0.9,
            "captureRules":{"requireLocationOnOpen":true,"maximumLocationAccuracyMeters":100,
            "automaticRecording":true,"allowPhotoImport":false,"allowRecordingImport":false}}}
            """;
        var policy = store.active(scope, "100", Instant.now());
        assertThat(policy.radiusMeters()).isEqualTo(500);
        assertThat(policy.captureRules().automaticRecording()).isTrue();
        assertThat(policy.captureRules().automaticCheckout()).isFalse();
        assertThat(policy.captureRules().maximumLocationAccuracyMeters()).isEqualTo(100);
        assertThat(signer.verify(request)).isTrue();
        assertThat(request.getHeader(RequestHeaders.PERMISSIONS)).isEqualTo("sales:context:read");
    }

    @Test void missingSalesPolicyFailsClosedWithoutFallingBackToCrm() {
        status = 409; body = "private upstream diagnostic";
        assertThatThrownBy(() -> store.active(scope, "100", Instant.now()))
            .isInstanceOf(StateConflictException.class).hasMessageNotContaining("private");
    }

    @Test void unavailableSalesReturnsSafeRetryableError() {
        status = 500; body = "private upstream diagnostic";
        assertThatThrownBy(() -> store.active(scope, "100", Instant.now()))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
            .hasMessageNotContaining("private");
    }

    @Test void mismatchedTenantCannotReadAnotherSalesPolicy() {
        when(scope.access().tenantId()).thenReturn(UUID.randomUUID().toString());
        assertThatThrownBy(() -> store.active(scope, "100", Instant.now())).isInstanceOf(AuthorizationDeniedException.class);
        assertThat(request).isNull();
    }
}
