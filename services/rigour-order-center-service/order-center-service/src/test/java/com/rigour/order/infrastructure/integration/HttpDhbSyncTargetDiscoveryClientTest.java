package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.integration.api.v1.DhbIntegrationInternalApi;
import com.rigour.order.application.port.out.DhbSyncTargetDiscoveryClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpDhbSyncTargetDiscoveryClientTest {
    private static final UUID SERVICE_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));

    @Test
    void discoversTargetsThroughInternalSignedServicePath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";
        server.expect(requestTo(base + DhbIntegrationInternalApi.SYNC_TARGETS_PATH))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE"))
                .andExpect(header(RequestHeaders.PRINCIPAL_ID, SERVICE_ID.toString()))
                .andExpect(header(RequestHeaders.PERMISSIONS,
                        "integration:dhb:read,integration:dhb:sync-discovery,integration:dhb:write"))
                .andRespond(withSuccess("""
                        [{
                          "taskId": "019fb000-0000-7000-8000-000000000020",
                          "tenantId": "019fb000-0000-7000-8000-000000000002",
                          "connectorId": "019fb000-0000-7000-8000-000000000010"
                        }]
                        """, MediaType.APPLICATION_JSON));

        DhbSyncTargetDiscoveryClient client = new HttpDhbSyncTargetDiscoveryClient(
                builder, signer(), base);

        var targets = client.discover(caller());

        assertThat(targets).singleElement().satisfies(target -> {
            assertThat(target.tenantId()).isEqualTo(UUID.fromString(
                    "019fb000-0000-7000-8000-000000000002"));
            assertThat(target.connectorId()).isEqualTo(UUID.fromString(
                    "019fb000-0000-7000-8000-000000000010"));
        });
        server.verify();
    }

    private static CallerIdentity caller() {
        return new CallerIdentity("SERVICE", SERVICE_ID, null, null, null,
                UUID.fromString("019fb000-0000-7000-8000-000000000003"), 0, 0, 0,
                Set.of("ORDER_SYNC_SERVICE"), Set.of(
                        "integration:dhb:read", "integration:dhb:write", "integration:dhb:sync-discovery"));
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
