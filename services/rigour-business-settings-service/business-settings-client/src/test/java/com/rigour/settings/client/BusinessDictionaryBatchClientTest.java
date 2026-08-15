package com.rigour.settings.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.settings.api.v1.BusinessDictionaryInternalApi;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BusinessDictionaryBatchClientTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000001");
    private static final String BASE_URL = "https://settings.test";

    @Test
    void deduplicatesExactValuesAndKeepsExplicitSourceName() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + BusinessDictionaryInternalApi.BASE_PATH + "/items/sync"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE"))
                .andExpect(header(RequestHeaders.TENANT_ID, TENANT_ID.toString()))
                .andExpect(jsonPath("$.moduleCode").value("COMMON"))
                .andExpect(jsonPath("$.dictCode").value("DHB_UNIT"))
                .andExpect(jsonPath("$.values.length()").value(1))
                .andExpect(jsonPath("$.values[0].value").value("BOX"))
                .andExpect(jsonPath("$.values[0].name").value("箱"))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));
        BusinessDictionaryBatchClient client = new BusinessDictionaryBatchClient(builder, signer(), BASE_URL);

        var audit = client.sync(BusinessDictionaryBatchClient.serviceCaller(
                "test-service", "TEST_DICTIONARY_SYNC", TENANT_ID), "PRODUCT", List.of(
                new BusinessDictionaryBatchClient.Observation(
                        "COMMON", "DHB_UNIT", "product.unit", "BOX", null),
                new BusinessDictionaryBatchClient.Observation(
                        "COMMON", "DHB_UNIT", "product.unit", "BOX", "箱")));

        assertThat(audit.unmapped()).isZero();
        assertThat(audit.revisions()).containsEntry("COMMON.DHB_UNIT", 6L);
        server.verify();
    }

    @Test
    void remoteFailureBecomesAuditWarningInsteadOfStoppingCaller() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + BusinessDictionaryInternalApi.BASE_PATH + "/items/sync"))
                .andRespond(withServerError());
        BusinessDictionaryBatchClient client = new BusinessDictionaryBatchClient(builder, signer(), BASE_URL);

        var audit = client.sync(BusinessDictionaryBatchClient.serviceCaller(
                "test-service", "TEST_DICTIONARY_SYNC", TENANT_ID), "ORDER", List.of(
                new BusinessDictionaryBatchClient.Observation(
                        "ORDER", "DHB_ORDER_STATUS", "order.status", "pending", null)));

        assertThat(audit.unmapped()).isEqualTo(1);
        assertThat(audit.revisions()).containsEntry("ORDER.DHB_ORDER_STATUS", -1L);
        assertThat(audit.issues()).singleElement().satisfies(issue ->
                assertThat(issue.sourceValue()).isEqualTo("pending"));
        server.verify();
    }

    private static String successResponse() {
        return """
                {
                  "code":"OK",
                  "message":"success",
                  "data":{
                    "effective":{
                      "dictionary":{
                        "id":"10000000-0000-7000-8000-000000000001",
                        "code":"DHB_UNIT",
                        "name":"订货宝计量单位",
                        "scopeType":"MODULE",
                        "scopeId":"COMMON",
                        "moduleCode":"COMMON",
                        "tenantId":null,
                        "baseDictId":null,
                        "status":"ACTIVE",
                        "sortNo":10,
                        "remark":null,
                        "version":0,
                        "revision":6
                      },
                      "items":[{
                        "id":"10000000-0000-7000-8000-000000000101",
                        "dictId":"10000000-0000-7000-8000-000000000001",
                        "parentId":null,
                        "levelNo":1,
                        "code":"AUTO_BOX",
                        "name":"箱",
                        "value":"BOX",
                        "sortNo":0,
                        "status":"ACTIVE",
                        "extraJson":null,
                        "version":0
                      }]
                    },
                    "observed":1,
                    "created":1,
                    "existing":0,
                    "blocked":0
                  }
                }
                """;
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of(
                "v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
