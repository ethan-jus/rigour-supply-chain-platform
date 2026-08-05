package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.application.port.out.DhbOrderSyncClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class HttpDhbOrderSyncClientTest {
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb000-0000-7000-8000-000000000010");

    @Test
    void followsCurrentIntegrationOrderContractAndMapsDetailsToOrderBatch() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID + "/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(100))
                .andExpect(jsonPath("$.updatedFrom").value("2026-08-01T00:00:00Z"))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "v1"))
                .andExpect(header(RequestHeaders.PERMISSIONS,
                        "integration:dhb:read,integration:dhb:write"))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "sourceId": "source-1",
                            "orderNumber": "ORD-1",
                            "status": "finished",
                            "amount": 12.50,
                            "createdAt": "2026-08-01T00:00:00Z",
                            "updatedAt": "2026-08-02T00:00:00Z",
                            "customerNumber": "C-1",
                            "paymentStatus": "paided",
                            "sourceFields": {
                              "OrderSN": "ORD-1",
                              "OrderTotal": "12.50",
                              "OrderDate": "2026-08-01 08:00:00"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID + "/ORD-1/content"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.autoMarkDownloaded").value(false))
                .andExpect(jsonPath("$.autoAudit").value(false))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "v1"))
                .andRespond(withSuccess("""
                        {
                          "orderNumber": "ORD-1",
                          "status": "finished",
                          "amount": 12.50,
                          "sourceFields": {
                            "OrderSN": "ORD-1",
                            "OrderProduct": [{
                              "orders_list_id": "LINE-1",
                              "Guid": "GOODS-1",
                              "OptionsGoodsNum": "SKU-1",
                              "Name": "商品一",
                              "ContentPrice": "12.50",
                              "ContentNumber": "1",
                              "ActualAmount": "12.50"
                            }],
                            "Ships": [{"ships_num": "SHIP-1", "status": "received"}]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        HttpDhbOrderSyncClient client = new HttpDhbOrderSyncClient(builder, signer(),
                JsonMapper.builder().build(), base);

        DhbOrderSyncClient.Collected collected = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1,
                        java.time.Instant.parse("2026-08-01T00:00:00Z"),
                        java.time.Instant.parse("2026-08-02T00:00:00Z")));

        assertThat(collected.fetched()).isEqualTo(1);
        assertThat(collected.completedObjects()).containsExactlyInAnyOrder("ORDER", "ORDER_DETAIL");
        DhbOrderImportBatch.OrderItem order = collected.batch().orders().getFirst();
        assertThat(order.sourceOrderNo()).isEqualTo("ORD-1");
        assertThat(order.detailIncluded()).isTrue();
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.sourceLineId()).isEqualTo("LINE-1");
            assertThat(line.skuNo()).isEqualTo("SKU-1");
        });
        assertThat(order.shipmentSnapshots()).singleElement()
                .extracting(DhbOrderImportBatch.OrderShipmentItem::sourceShipmentNo)
                .isEqualTo("SHIP-1");
        server.verify();
    }

    private static CallerIdentity caller() {
        UUID userId = UUID.fromString("019fb000-0000-7000-8000-000000000001");
        return new CallerIdentity("TENANT", userId,
                UUID.fromString("019fb000-0000-7000-8000-000000000002"), userId, null,
                UUID.fromString("019fb000-0000-7000-8000-000000000003"), 2, 3, 4,
                Set.of("ORDER_OPERATOR"), Set.of("integration:dhb:read", "integration:dhb:write"));
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
