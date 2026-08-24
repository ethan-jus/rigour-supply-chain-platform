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
                .andExpect(jsonPath("$.dictionaryCode").value("PRODUCT_UNIT"))
                .andExpect(jsonPath("$.values.length()").value(1))
                .andExpect(jsonPath("$.values[0].value").value("BOX"))
                .andExpect(jsonPath("$.values[0].name").value("箱"))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));
        BusinessDictionaryBatchClient client = new BusinessDictionaryBatchClient(builder, signer(), BASE_URL);

        var audit = client.sync(BusinessDictionaryBatchClient.serviceCaller(
                "test-service", "TEST_DICTIONARY_SYNC", TENANT_ID), "PRODUCT", List.of(
                new BusinessDictionaryBatchClient.Observation(
                        "PRODUCT_UNIT", "product.unit", "BOX", null),
                new BusinessDictionaryBatchClient.Observation(
                        "PRODUCT_UNIT", "product.unit", "BOX", "箱")));

        assertThat(audit.unmapped()).isZero();
        assertThat(audit.revisions()).containsEntry("PRODUCT_UNIT", 6L);
        server.verify();
    }

    @Test
    void resolvesSourceValueUsingSettingsGeneratedItemCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + BusinessDictionaryInternalApi.BASE_PATH + "/items/sync"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.dictionaryCode").value("DHB_STAFF_TYPE"))
                .andExpect(jsonPath("$.values[0].value").value("salesman"))
                .andRespond(withSuccess(staffTypeResponse(), MediaType.APPLICATION_JSON));
        BusinessDictionaryBatchClient client = new BusinessDictionaryBatchClient(builder, signer(), BASE_URL);

        var audit = client.sync(BusinessDictionaryBatchClient.serviceCaller(
                "test-service", "TEST_DICTIONARY_SYNC", TENANT_ID), "STAFF", List.of(
                new BusinessDictionaryBatchClient.Observation(
                        "DHB_STAFF_TYPE", "staff.staffType", "salesman", null)));

        assertThat(audit.unmapped()).isZero();
        assertThat(audit.issues()).isEmpty();
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
                        "ORDER_STATUS", "order.status", "pending", null)));

        assertThat(audit.unmapped()).isEqualTo(1);
        assertThat(audit.revisions()).containsEntry("ORDER_STATUS", -1L);
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
                        "id":1,
                        "dictionaryCode":"PRODUCT_UNIT",
                        "dictionaryName":"商品单位",
                        "dictionaryType":"COMMON",
                        "remark":null,
                        "revision":6
                      },
                      "items":[{
                        "id":11,
                        "dictionaryCode":"PRODUCT_UNIT",
                        "dictionaryItemLevel":1,
                        "parentDictionaryItemCode":null,
                        "dictionaryItemCode":"BOX",
                        "dictionaryItemName":"箱",
                        "remark":null,
                        "ordinal":0,
                        "revision":1
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

    private static String staffTypeResponse() {
        return """
                {
                  "code":"OK",
                  "message":"success",
                  "data":{
                    "effective":{
                      "dictionary":{
                        "id":2,
                        "dictionaryCode":"DHB_STAFF_TYPE",
                        "dictionaryName":"订货宝员工类型",
                        "dictionaryType":"CRM",
                        "remark":null,
                        "revision":2
                      },
                      "items":[{
                        "id":21,
                        "dictionaryCode":"DHB_STAFF_TYPE",
                        "dictionaryItemLevel":1,
                        "parentDictionaryItemCode":null,
                        "dictionaryItemCode":"SALESMAN",
                        "dictionaryItemName":"业务员",
                        "remark":"外部来源值：salesman",
                        "ordinal":10,
                        "revision":1
                      }]
                    },
                    "observed":1,
                    "created":0,
                    "existing":1,
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
