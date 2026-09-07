package com.rigour.integration.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.integration.api.v1.DhbIntegrationInternalApi;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ExternalObjectMappingClientTest {
    private static final String BASE_URL = "https://integration.test";
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb900-0000-7000-8000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("019fb900-0000-7000-8000-000000000003");

    @Test
    void upsertRequestsJsonResponseWithTrustedServiceIdentity() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + DhbIntegrationInternalApi.OBJECT_MAPPINGS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(ExternalObjectMappingClientTest::assertTrustedServiceIdentityHeaders)
                .andRespond(withSuccess("{\"requested\":1,\"accepted\":1}", MediaType.APPLICATION_JSON));

        ExternalObjectMappingClient client =
                new ExternalObjectMappingClient(builder, signer(), BASE_URL, "rigour-merchant-crm-service");

        var result = client.upsert(TENANT_ID, List.of(new ExternalObjectMappingCommand(
                CONNECTOR_ID,
                "DHB",
                "CUSTOMER",
                "11800946",
                "32256",
                "CRM",
                "CUSTOMER",
                3057L,
                "CUS202608268374",
                "ACTIVE",
                RUN_ID,
                Instant.parse("2026-08-26T05:38:46Z"),
                null,
                "payload-hash",
                null,
                "CRM订货宝客户同步映射")));

        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.accepted()).isEqualTo(1);
        server.verify();
    }

    @Test
    void upsertSplitsLargeMappingBatchesToAvoidLongSingleRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + DhbIntegrationInternalApi.OBJECT_MAPPINGS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("{\"requested\":200,\"accepted\":200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + DhbIntegrationInternalApi.OBJECT_MAPPINGS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("{\"requested\":5,\"accepted\":5}", MediaType.APPLICATION_JSON));
        ExternalObjectMappingClient client =
                new ExternalObjectMappingClient(builder, signer(), BASE_URL, "rigour-merchant-crm-service");

        var result = client.upsert(TENANT_ID, java.util.stream.IntStream.range(0, 205)
                .mapToObj(index -> mapping("32256-" + index))
                .toList());

        assertThat(result.requested()).isEqualTo(205);
        assertThat(result.accepted()).isEqualTo(205);
        server.verify();
    }

    private static void assertTrustedServiceIdentityHeaders(org.springframework.http.HttpRequest request) {
        assertThat(request.getHeaders().getFirst(RequestHeaders.PRINCIPAL_SCOPE)).isEqualTo("SERVICE");
        assertThat(request.getHeaders().getFirst(RequestHeaders.TENANT_ID)).isEqualTo(TENANT_ID.toString());
        assertThat(request.getHeaders().getFirst(RequestHeaders.SESSION_ID)).isNotBlank();
        assertThat(request.getHeaders().getFirst(RequestHeaders.PERMISSIONS))
                .isEqualTo("integration:dhb:write");
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

    private static ExternalObjectMappingCommand mapping(String sourceObjectNo) {
        return new ExternalObjectMappingCommand(
                CONNECTOR_ID,
                "DHB",
                "CUSTOMER",
                sourceObjectNo,
                sourceObjectNo,
                "CRM",
                "CUSTOMER",
                3057L,
                "CUS202608268374",
                "ACTIVE",
                RUN_ID,
                Instant.parse("2026-08-26T05:38:46Z"),
                null,
                "payload-hash",
                null,
                "CRM订货宝客户同步映射");
    }
}
