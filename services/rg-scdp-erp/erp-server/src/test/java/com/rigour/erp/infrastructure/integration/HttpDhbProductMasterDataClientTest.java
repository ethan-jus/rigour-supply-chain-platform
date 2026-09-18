package com.rigour.erp.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.integration.api.v1.DhbProductApi;
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
import tools.jackson.databind.json.JsonMapper;

class HttpDhbProductMasterDataClientTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb100-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb100-0000-7000-8000-000000000003");
    private static final UUID JOB_ID = UUID.fromString("019fb100-0000-7000-8000-000000000010");
    private static final UUID SERVICE_ID = UUID.nameUUIDFromBytes(
            "service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8));

    @Test
    void startsMediaJobBeforeQueryingProductObjectKeys() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";

        expectProductBucket(server, base, "T", """
                        {
                          "total": 1,
                          "items": [{
                            "sourceId": "P-1",
                            "code": "SPU-1",
                            "name": "商品一",
                            "putaway": "F",
                            "sourceLifecycle": "NORMAL",
                            "images": [{
                              "sourceResourceId": "IMG-1",
                              "sourceGoodsId": "P-1",
                              "originalName": "主图.png",
                              "fileName": "main.png",
                              "sortOrder": 1,
                              "objectKey": "tenant/product-images/P-1/IMG-1/hash.png"
                            }],
                            "customFields": {},
                            "skus": [],
                          "sourceFields": {
                            "units": "件",
                            "goods_tag": ["TAG-1"]
                          }
                          }]
                        }
                        """);
        expectProductBucket(server, base, "A", emptyProductPage());
        expectProductBucket(server, base, "N", emptyProductPage());
        expectProductBucket(server, base, "R", emptyProductPage());
        expectProductBucket(server, base, "F", """
                        {
                          "total": 1,
                          "items": [{
                            "sourceId": "P-1",
                            "code": "SPU-1",
                            "name": "商品一",
                            "putaway": "F",
                            "sourceLifecycle": "SOURCE_DELETED",
                            "images": [{
                              "sourceResourceId": "IMG-1",
                              "sourceGoodsId": "P-1",
                              "originalName": "主图.png",
                              "fileName": "main.png",
                              "sortOrder": 1,
                              "objectKey": "tenant/product-images/P-1/IMG-1/hash.png"
                            }],
                            "customFields": {},
                            "skus": [],
                          "sourceFields": {
                            "units": "件",
                            "goods_tag": ["TAG-1"]
                          }
                          }]
                        }
                        """);

        var client = new HttpDhbProductMasterDataClient(builder, signer(), JsonMapper.builder().build(), base);
        var collected = client.collect(caller(), CONNECTOR_ID, MasterDataObjectType.PRODUCT_SPU, 1);

        assertThat(collected.products()).singleElement().satisfies(product -> {
            assertThat(product.sourceId()).isEqualTo("P-1");
            assertThat(product.sourceLifecycle()).isEqualTo("SOURCE_DELETED");
            assertThat(product.sourceFields()).containsEntry("units", "件");
            assertThat(product.sourceFields()).containsKey("goods_tag");
            assertThat(product.images()).singleElement().satisfies(image ->
                    assertThat(image.objectKey()).isEqualTo("tenant/product-images/P-1/IMG-1/hash.png"));
        });
        server.verify();
    }

    private static void expectProductBucket(MockRestServiceServer server, String base,
                                            String status, String queryResponse) {
        server.expect(requestTo(base + DhbProductApi.BASE_PATH + "/" + CONNECTOR_ID + "/media-sync"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE"))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(50))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.putaway").value("A"))
                .andRespond(withSuccess("""
                        {
                          "jobId": "%s",
                          "connectorId": "%s",
                          "status": "SUCCEEDED",
                          "totalImages": 1,
                          "completedImages": 1,
                          "failedImages": 0
                        }
                        """.formatted(JOB_ID, CONNECTOR_ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + DhbProductApi.BASE_PATH + "/" + CONNECTOR_ID + "/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.putaway").value("A"))
                .andExpect(jsonPath("$.mediaJobId").value(JOB_ID.toString()))
                .andRespond(withSuccess(queryResponse, MediaType.APPLICATION_JSON));
    }

    private static String emptyProductPage() {
        return """
                {
                  "total": 0,
                  "items": []
                }
                """;
    }

    private static CallerIdentity caller() {
        return new CallerIdentity("SERVICE", SERVICE_ID, TENANT_ID, null, null,
                UUID.fromString("019fb100-0000-7000-8000-000000000006"), 0, 0, 0,
                Set.of("ERP_PRODUCT_SYNC_SERVICE"), Set.of("integration:dhb:read"));
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of(
                "v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
