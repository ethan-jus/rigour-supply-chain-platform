package com.rigour.merchant.infrastructure.integration;

import com.rigour.integration.api.v1.DhbCustomerApi;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpDhbCrmMasterDataClientTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb500-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb500-0000-7000-8000-000000000002");
    private static final UUID SERVICE_ID = UUID.fromString("019fb500-0000-7000-8000-000000000003");

    @Test
    void requestsCompleteCustomerScopeAndPreservesSourceFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String uri = "https://integration.test" + DhbCustomerApi.BASE_PATH + "/" + CONNECTOR_ID + "/query";
        server.expect(requestTo(uri))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE"))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(100))
                .andExpect(jsonPath("$.status").value(3))
                .andExpect(jsonPath("$.dataType").value(3))
                .andRespond(withSuccess("""
                        {"total":4,"items":[{
                          "sourceId":"CLIENT-GUID-1","clientGuid":"CLIENT-GUID-1",
                          "account":"customer001","companyName":"示例客户","number":"C-001",
                          "status":"T","updatedAt":"2026-08-01T00:00:00Z",
                          "sourceFields":{"clientGUID":"CLIENT-GUID-1","clientNO":"C-001","futureField":42}
                        },{
                          "sourceId":"CLIENT-GUID-2","clientGuid":"CLIENT-GUID-2",
                          "companyName":"停用客户","number":"C-002","status":"F",
                          "sourceFields":{"clientGUID":"CLIENT-GUID-2","clientNO":"C-002"}
                        },{
                          "sourceId":"CLIENT-GUID-3","clientGuid":"CLIENT-GUID-3",
                          "companyName":"待激活客户","number":"C-003","status":"A",
                          "sourceFields":{"clientGUID":"CLIENT-GUID-3","clientNO":"C-003"}
                        },{
                          "sourceId":"CLIENT-GUID-4","clientGuid":"CLIENT-GUID-4",
                          "companyName":"待审核客户","number":"C-004","status":"C",
                          "sourceFields":{"clientGUID":"CLIENT-GUID-4","clientNO":"C-004"}
                        }]}
                        """, MediaType.APPLICATION_JSON));

        var client = new HttpDhbCrmMasterDataClient(builder, signer(), "https://integration.test");
        var result = client.collect(caller(), CONNECTOR_ID, CrmMasterDataObjectType.CUSTOMER, 1);

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.pages()).isEqualTo(1);
        assertThat(result.items()).hasSize(4);
        assertThat(result.items()).extracting("sourceStatus").containsExactly("T", "F", "A", "C");
        assertThat(result.items().getFirst()).satisfies(item -> {
            assertThat(item.sourceId()).isEqualTo("CLIENT-GUID-1");
            assertThat(item.sourceCode()).isEqualTo("C-001");
            assertThat(item.sourceFields()).containsEntry("futureField", 42);
        });
        server.verify();
    }

    @Test
    void propagatesCustomerAreaParentIdIntoSourceFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String uri = "https://integration.test" + DhbCustomerApi.BASE_PATH + "/"
                + CONNECTOR_ID + "/areas/query";
        server.expect(requestTo(uri))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"items":[{"sourceId":"AREA-2","name":"上海市","erpId":"ERP-2",
                          "parentSourceId":"AREA-1","sourceFields":{"AreaName":"上海市"}}]}
                        """, MediaType.APPLICATION_JSON));

        var client = new HttpDhbCrmMasterDataClient(builder, signer(), "https://integration.test");
        var result = client.collect(caller(), CONNECTOR_ID, CrmMasterDataObjectType.CUSTOMER_AREA, 1);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceId()).isEqualTo("AREA-2");
            assertThat(item.sourceFields()).containsEntry("parentID", "AREA-1");
        });
        server.verify();
    }

    private static CallerIdentity caller() {
        return new CallerIdentity("SERVICE", SERVICE_ID, TENANT_ID, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("CRM_SYNC_SERVICE"),
                Set.of("integration:dhb:read"));
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1",
                Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
