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

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID + "/ORD-1/wait-ships"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}"))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "v1"))
                .andRespond(withSuccess("""
                        {
                          "orderNumber": "ORD-1",
                          "shipped": [{
                            "sourceId": "SHIP-ID-1",
                            "shipmentNo": "SHIP-1",
                            "status": "receivedin",
                            "logisticsName": "顺丰",
                            "trackingNo": "SF-1",
                            "shipmentAt": "2026-08-02T00:00:00Z",
                            "warehouseNo": "WH-1",
                            "warehouseName": "主仓",
                            "lines": [{
                              "sourceLineId": "SHIP-LINE-1",
                              "orderLineId": "LINE-1",
                              "productId": "GOODS-1",
                              "skuNo": "SKU-1",
                              "productCode": "G-1",
                              "productName": "商品一",
                              "unit": "件",
                              "quantity": 1
                            }]
                          }],
                          "waitStock": []
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID + "/shipments/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(100))
                .andExpect(jsonPath("$.isApi").value("F,T"))
                .andExpect(jsonPath("$.updatedFrom").value("2026-08-01T00:00:00Z"))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "v1"))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "sourceId": "SHIP-ID-1",
                            "shipmentNumber": "SHIP-1",
                            "orderNumber": "ORD-1",
                            "status": "receivedin",
                            "statusName": "待收货",
                            "typeId": "10",
                            "typeName": "销售出库",
                            "customerNumber": "C-1",
                            "customerName": "客户一",
                            "warehouseNumber": "WH-1",
                            "warehouseName": "主仓",
                            "shipmentAt": "2026-08-02T00:00:00Z",
                            "sourceFields": {
                              "ships_num": "SHIP-1",
                              "ships_id": "SHIP-ID-1",
                              "orders_num": "ORD-1",
                              "stock_num": "WH-1"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/shipments/SHIP-1/content"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}"))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "v1"))
                .andRespond(withSuccess("""
                        {
                          "shipmentNumber": "SHIP-1",
                          "sourceFields": {
                            "ships_num": "SHIP-1",
                            "orders_num": "ORD-1",
                            "stock_num": "WH-1",
                            "list": [{
                              "ships_list_id": "SHIP-LINE-1",
                              "goods_guid": "GOODS-1",
                              "options_goods_num": "SKU-1",
                              "goods_num": "G-1",
                              "goods_name": "商品一",
                              "ships_number": "1",
                              "orders_list_info": {
                                "order_units_price": "12.50",
                                "actual_amount": "12.50",
                                "order_units_name": "件"
                              }
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/returns/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(100))
                .andExpect(jsonPath("$.isApi").value("F,T"))
                .andExpect(jsonPath("$.updatedFrom").value("2026-08-01T00:00:00Z"))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "sourceId": "RET-ID-1",
                            "returnNumber": "RET-1",
                            "orderNumber": "ORD-1",
                            "status": "finished",
                            "returnAmount": 3.00,
                            "settlementAmount": 2.50,
                            "returnedAt": "2026-08-02T00:00:00Z",
                            "updatedAt": "2026-08-02T01:00:00Z",
                            "customerNumber": "C-1",
                            "sourceFields": {
                              "ReturnsSN": "RET-1",
                              "ReturnsTotal": "3.00"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/returns/RET-1/content"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}"))
                .andRespond(withSuccess("""
                        {
                          "returnNumber": "RET-1",
                          "lines": [{
                            "sourceId": "RET-LINE-1",
                            "productGuid": "GOODS-1",
                            "skuNumber": "SKU-1",
                            "productCode": "G-1",
                            "productName": "商品一",
                            "quantity": 1,
                            "confirmedQuantity": 1,
                            "unitPrice": 3.00,
                            "confirmedPrice": 2.50,
                            "unit": "件",
                            "warehouseNumber": "WH-1",
                            "warehouseName": "主仓",
                            "sourceFields": {}
                          }],
                          "sourceFields": {
                            "ReturnsSN": "RET-1",
                            "OrdersNum": "ORD-1"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/receipts/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(100))
                .andExpect(jsonPath("$.updatedFrom").value("2026-08-01T00:00:00Z"))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "sourceId": "REC-ID-1",
                            "receiptNumber": "REC-1",
                            "orderNumber": "ORD-1",
                            "amount": 12.50,
                            "status": "pend_receipted",
                            "transactionAt": "2026-08-02T00:00:00Z",
                            "createdAt": "2026-08-02T00:00:00Z",
                            "updatedAt": "2026-08-02T01:00:00Z",
                            "sourceFields": {
                              "ReceiptsNum": "REC-1",
                              "Amount": "12.50"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/payments/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(100))
                .andExpect(jsonPath("$.createdFrom").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.createdTo").value("2026-08-02T00:00:00Z"))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "sourceId": "PAY-ID-1",
                            "paymentNumber": "PAY-1",
                            "receiptNumber": "REC-1",
                            "orderNumber": "ORD-1",
                            "amount": 2.50,
                            "status": "pend_receipted",
                            "transactionAt": "2026-08-02T00:00:00Z",
                            "createdAt": "2026-08-02T00:00:00Z",
                            "sourceFields": {
                              "PaymentNum": "PAY-1",
                              "ReceiptsNum": "REC-1",
                              "Amount": "2.50"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        HttpDhbOrderSyncClient client = new HttpDhbOrderSyncClient(builder, signer(),
                JsonMapper.builder().build(), base);

        DhbOrderSyncClient.Collected collected = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1,
                        java.time.Instant.parse("2026-08-01T00:00:00Z"),
                        java.time.Instant.parse("2026-08-02T00:00:00Z")));

        assertThat(collected.fetched()).isEqualTo(5);
        assertThat(collected.completedObjects()).containsExactlyInAnyOrder(
                "ORDER", "ORDER_DETAIL", "SHIPMENT_LOGISTICS", "SHIPMENT", "SHIPMENT_DETAIL",
                "RETURN", "RETURN_DETAIL", "RECEIPT", "PAYMENT");
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
        assertThat(collected.batch().shipmentLogistics()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.orderNo()).isEqualTo("ORD-1");
            assertThat(snapshot.shipped()).singleElement()
                    .extracting(DhbOrderImportBatch.ShipmentLogisticsRecord::shipmentNo)
                    .isEqualTo("SHIP-1");
        });
        assertThat(collected.batch().shipments()).singleElement().satisfies(shipment -> {
            assertThat(shipment.shipmentNo()).isEqualTo("SHIP-1");
            assertThat(shipment.orderNo()).isEqualTo("ORD-1");
            assertThat(shipment.detailIncluded()).isTrue();
            assertThat(shipment.lines()).singleElement()
                    .extracting(DhbOrderImportBatch.ShipmentLineItem::skuNo)
                    .isEqualTo("SKU-1");
        });
        assertThat(collected.batch().returns()).singleElement().satisfies(item -> {
            assertThat(item.returnNo()).isEqualTo("RET-1");
            assertThat(item.detailIncluded()).isTrue();
            assertThat(item.lines()).singleElement()
                    .extracting(DhbOrderImportBatch.ReturnLineItem::skuNo)
                    .isEqualTo("SKU-1");
        });
        assertThat(collected.batch().financialDocuments()).hasSize(2);
        assertThat(collected.batch().financialDocuments()).extracting(
                DhbOrderImportBatch.FinancialItem::documentType)
                .containsExactlyInAnyOrder("RECEIPT", "PAYMENT");
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
