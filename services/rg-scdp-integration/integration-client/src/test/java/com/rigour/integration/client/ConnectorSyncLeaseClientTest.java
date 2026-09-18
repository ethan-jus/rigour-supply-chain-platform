package com.rigour.integration.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.integration.api.v1.DhbConnectorLeaseApi;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ConnectorSyncLeaseClientTest {
    private static final String BASE_URL = "https://integration.test";
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb900-0000-7000-8000-000000000002");
    private static final String TOKEN = "019fb900-0000-7000-8000-000000000003";

    @Test
    void leaseCoversActionAndUsesExactTokenForRelease() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String basePath = BASE_URL + DhbConnectorLeaseApi.BASE_PATH + "/" + CONNECTOR_ID;
        server.expect(requestTo(basePath)).andExpect(method(HttpMethod.POST))
                .andExpect(ConnectorSyncLeaseClientTest::assertTrustedServiceIdentityHeaders)
                .andRespond(withSuccess(leaseResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(basePath + "/" + TOKEN)).andExpect(method(HttpMethod.DELETE))
                .andExpect(ConnectorSyncLeaseClientTest::assertTrustedServiceIdentityHeaders)
                .andRespond(withSuccess());
        try (ConnectorSyncLeaseClient client = new ConnectorSyncLeaseClient(
                builder, signer(), BASE_URL, "test-service")) {
            String value = client.execute(TENANT_ID, CONNECTOR_ID, () -> "done");
            assertThat(value).isEqualTo("done");
        }
        server.verify();
    }

    @Test
    void remoteStableConflictBecomesLocalBusiness409() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + DhbConnectorLeaseApi.BASE_PATH + "/" + CONNECTOR_ID))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"SYNC_ALREADY_RUNNING\",\"message\":\"busy\"}"));
        try (ConnectorSyncLeaseClient client = new ConnectorSyncLeaseClient(
                builder, signer(), BASE_URL, "test-service")) {
            assertThatThrownBy(() -> client.execute(TENANT_ID, CONNECTOR_ID, () -> "never"))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SYNC_ALREADY_RUNNING));
        }
        server.verify();
    }

    @Test
    void guardedExecutionSynchronouslyRenewsBeforeCommit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String basePath = BASE_URL + DhbConnectorLeaseApi.BASE_PATH + "/" + CONNECTOR_ID;
        server.expect(requestTo(basePath)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(futureLeaseResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(basePath + "/" + TOKEN + "/renew"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(futureLeaseResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(basePath + "/" + TOKEN)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());
        try (ConnectorSyncLeaseClient client = new ConnectorSyncLeaseClient(
                builder, signer(), BASE_URL, "test-service")) {
            String value = client.executeWithLeaseGuard(TENANT_ID, CONNECTOR_ID, guard -> {
                guard.ensureActive();
                return "committed";
            });
            assertThat(value).isEqualTo("committed");
        }
        server.verify();
    }

    @Test
    void guardedExecutionRejectsRenewalForAnotherLease() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String basePath = BASE_URL + DhbConnectorLeaseApi.BASE_PATH + "/" + CONNECTOR_ID;
        server.expect(requestTo(basePath)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(futureLeaseResponse(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(basePath + "/" + TOKEN + "/renew"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(futureLeaseResponse("another-lease-token"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(basePath + "/" + TOKEN)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());
        try (ConnectorSyncLeaseClient client = new ConnectorSyncLeaseClient(
                builder, signer(), BASE_URL, "test-service")) {
            assertThatThrownBy(() -> client.executeWithLeaseGuard(TENANT_ID, CONNECTOR_ID, guard -> {
                guard.ensureActive();
                return "must-not-commit";
            })).isInstanceOfSatisfying(BusinessException.class, error ->
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SYNC_ALREADY_RUNNING));
        }
        server.verify();
    }

    private static String leaseResponse() {
        return futureLeaseResponse();
    }

    private static String futureLeaseResponse() {
        return futureLeaseResponse(TOKEN);
    }

    private static String futureLeaseResponse(String token) {
        return """
                {
                  "connectorId":"%s",
                  "token":"%s",
                  "expiresAt":"%s",
                  "ttlSeconds":120
                }
                """.formatted(CONNECTOR_ID, token, Instant.now().plusSeconds(120));
    }

    private static void assertTrustedServiceIdentityHeaders(org.springframework.http.HttpRequest request) {
        assertThat(request.getHeaders().getFirst(RequestHeaders.PRINCIPAL_SCOPE)).isEqualTo("SERVICE");
        assertThat(request.getHeaders().getFirst(RequestHeaders.TENANT_ID)).isEqualTo(TENANT_ID.toString());
        assertThat(request.getHeaders().getFirst(RequestHeaders.SESSION_ID)).isNotBlank();
        assertThat(request.getHeaders().getFirst(RequestHeaders.SESSION_VERSION)).isEqualTo("0");
        assertThat(request.getHeaders().getFirst(RequestHeaders.USER_SECURITY_VERSION)).isEqualTo("0");
        assertThat(request.getHeaders().getFirst(RequestHeaders.TENANT_POLICY_VERSION)).isEqualTo("0");
        assertThat(request.getHeaders().getFirst(RequestHeaders.PERMISSIONS))
                .isEqualTo("integration:dhb:lease");
        assertThat(request.getHeaders().getFirst(RequestHeaders.CONTEXT_KEY_ID)).isEqualTo("v1");
        assertThat(request.getHeaders().getFirst(RequestHeaders.CONTEXT_TIMESTAMP)).isNotBlank();
        assertThat(request.getHeaders().getFirst(RequestHeaders.CONTEXT_SIGNATURE)).isNotBlank();
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of(
                "v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
