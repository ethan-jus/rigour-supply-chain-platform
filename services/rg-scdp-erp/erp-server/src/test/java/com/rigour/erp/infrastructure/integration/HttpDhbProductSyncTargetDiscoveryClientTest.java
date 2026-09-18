package com.rigour.erp.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.integration.api.v1.DhbIntegrationInternalApi;
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

class HttpDhbProductSyncTargetDiscoveryClientTest {
    private static final UUID SERVICE_ID = UUID.nameUUIDFromBytes(
            "service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8));

    @Test
    void discoversOnlyProductMasterDataTargetsWithSignedQuery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";
        server.expect(requestTo(base + DhbIntegrationInternalApi.SYNC_TARGETS_PATH
                        + "?objectType=PRODUCT_MASTER_DATA"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE"))
                .andExpect(header(RequestHeaders.PRINCIPAL_ID, SERVICE_ID.toString()))
                .andExpect(header(RequestHeaders.PERMISSIONS,
                        "integration:dhb:sync-discovery"))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "v1"))
                .andRespond(withSuccess("""
                        [{
                          "taskId": "019fb100-0000-7000-8000-000000000004",
                          "tenantId": "019fb100-0000-7000-8000-000000000001",
                          "connectorId": "019fb100-0000-7000-8000-000000000003"
                        }]
                        """, MediaType.APPLICATION_JSON));

        var targets = new HttpDhbProductSyncTargetDiscoveryClient(
                builder, signer(), base).discover(caller());

        assertThat(targets).singleElement().satisfies(target -> {
            assertThat(target.tenantId()).isEqualTo(UUID.fromString(
                    "019fb100-0000-7000-8000-000000000001"));
            assertThat(target.connectorId()).isEqualTo(UUID.fromString(
                    "019fb100-0000-7000-8000-000000000003"));
        });
        server.verify();
    }

    private static CallerIdentity caller() {
        return new CallerIdentity("SERVICE", SERVICE_ID, null, null, null,
                UUID.fromString("019fb100-0000-7000-8000-000000000006"), 0, 0, 0,
                Set.of("ERP_PRODUCT_SYNC_SERVICE"),
                Set.of("integration:dhb:sync-discovery"));
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of(
                "v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
