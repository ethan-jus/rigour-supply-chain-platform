package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncMode;
import com.rigour.order.api.v1.model.DhbOrderSyncScope;
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
                .andExpect(jsonPath("$.orderStatus").value("all"))
                .andExpect(jsonPath("$.exceptionStatus").value("all"))
                .andExpect(jsonPath("$.apiStatus").value("all"))
                .andExpect(jsonPath("$.updatedFrom").doesNotExist())
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
                              "ActualAmount": "12.50",
                              "ContentPurchasePrice": "8.00",
                              "ConversionNumber": "12",
                              "OfferPrice": "1.00",
                              "GoodsWeight": "2.50",
                              "ContentPercent": "1.0000",
                              "isPre": 1,
                              "conType": "g",
                              "InvoiceTax": "13%"
                            }],
                            "UpdateDate": "2026-08-02 09:30:00",
                            "OrderPayStatus": "unoblig",
                            "OrderUpdateTime": "2026-08-02 09:00:00",
                            "OrderException": "T",
                            "OrderSendType": "express",
                            "lastOrderAt": "2026-08-02 08:30:00",
                            "ClientTypeName": "批发客户",
                            "ClientTagName": "重点客户",
                            "ClientAreaName": "华北区",
                            "StaffName": "业务员甲",
                            "StaffMobile": "13800000000",
                            "AssistStaff": [{"StaffName": "辅助员乙"}],
                            "OrderAuditTime": "2026-08-02 09:00:00",
                            "PayForm": "后付",
                            "GoodsWeight": "2.5000",
                            "Taxation": "1.2000",
                            "DiscountTotal": "10.3000",
                            "OrderFreight": "5.0000",
                            "ApplyTotal": "0.0000",
                            "CouponDiscountedAmount": "0.5000",
                            "ClientRemark": [{"content": "请尽快发货"}],
                            "internalComm": "内部备注",
                            "Invoice": {"invoice_title": "客户公司", "invoice_content": "商品", "bank": "银行", "bank_account": "123", "taxpayer_number": "税号", "invoice_type": "增值税专用发票"},
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
                .andExpect(jsonPath("$.updatedFrom").doesNotExist())
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
                .andExpect(jsonPath("$.isApi").value("All"))
                .andExpect(jsonPath("$.updatedFrom").doesNotExist())
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
                .andExpect(jsonPath("$.updatedFrom").doesNotExist())
                .andExpect(jsonPath("$.status").value("all"))
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
                .andExpect(jsonPath("$.createdFrom").doesNotExist())
                .andExpect(jsonPath("$.createdTo").doesNotExist())
                .andExpect(jsonPath("$.status").value("all"))
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
                        java.time.Instant.parse("2026-08-02T00:00:00Z"),
                        com.rigour.order.api.v1.model.DhbOrderSyncScope.ALL, DhbOrderSyncMode.FULL));

        assertThat(collected.fetched()).isEqualTo(5);
        assertThat(collected.completedObjects()).containsExactlyInAnyOrder(
                "ORDER", "ORDER_DETAIL", "SHIPMENT_LOGISTICS", "SHIPMENT", "SHIPMENT_DETAIL",
                "RETURN", "RETURN_DETAIL", "RECEIPT", "PAYMENT");
        DhbOrderImportBatch.OrderItem order = collected.batch().orders().getFirst();
        assertThat(order.sourceOrderNo()).isEqualTo("ORD-1");
        assertThat(order.detailIncluded()).isTrue();
        assertThat(order.paymentStatus()).isEqualTo("unoblig");
        assertThat(order.sourceUpdateTime()).isEqualTo("2026-08-02 09:30:00");
        assertThat(order.sourceUpdatedAt()).isEqualTo(java.time.Instant.parse("2026-08-02T01:30:00Z"));
        assertThat(order.sourceExceptionStatus()).isEqualTo("T");
        assertThat(order.sourceSendType()).isEqualTo("express");
        assertThat(order.sourceLastOrderAt()).isEqualTo("2026-08-02 08:30:00");
        assertThat(order.customerType()).isEqualTo("批发客户");
        assertThat(order.customerTag()).isEqualTo("重点客户");
        assertThat(order.customerArea()).isEqualTo("华北区");
        assertThat(order.salesPerson()).isEqualTo("业务员甲");
        assertThat(order.assistantSalesPersons()).isEqualTo("辅助员乙");
        assertThat(order.settlementMethod()).isEqualTo("后付");
        assertThat(order.goodsWeight()).isEqualByComparingTo("2.5000");
        assertThat(order.freightAmount()).isEqualByComparingTo("5.0000");
        assertThat(order.couponDiscountedAmount()).isEqualByComparingTo("0.5000");
        assertThat(order.customerRemark()).contains("请尽快发货");
        assertThat(order.invoiceTitle()).isEqualTo("客户公司");
        assertThat(order.invoiceType()).isEqualTo("增值税专用发票");
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.sourceLineId()).isEqualTo("LINE-1");
            assertThat(line.skuNo()).isEqualTo("SKU-1");
            assertThat(line.purchasePrice()).isEqualByComparingTo("8.00");
            assertThat(line.preSale()).isEqualTo("1");
            assertThat(line.contentType()).isEqualTo("g");
            assertThat(line.invoiceTax()).isEqualTo("13%");
            assertThat(line.contentPercent()).isEqualByComparingTo("1.0000");
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

    @Test
    void orderScopeOnlyFetchesOrdersAndDetails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID + "/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(100))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "orderNumber": "ORDER-ONLY-1",
                            "status": "pending",
                            "amount": 15.00,
                            "sourceFields": {"OrderSN": "ORDER-ONLY-1"}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/ORDER-ONLY-1/content"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{}"))
                .andRespond(withSuccess("""
                        {
                          "orderNumber": "ORDER-ONLY-1",
                          "sourceFields": {"OrderSN": "ORDER-ONLY-1", "OrderProduct": []}
                        }
                        """, MediaType.APPLICATION_JSON));

        HttpDhbOrderSyncClient client = new HttpDhbOrderSyncClient(builder, signer(),
                JsonMapper.builder().build(), base);
        DhbOrderSyncClient.Collected collected = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1, null, null, DhbOrderSyncScope.ORDER));

        assertThat(collected.objectType()).isEqualTo("ORDER");
        assertThat(collected.fetched()).isEqualTo(1);
        assertThat(collected.completedObjects()).containsExactlyInAnyOrder("ORDER", "ORDER_DETAIL");
        assertThat(collected.batch().orders()).singleElement()
                .satisfies(order -> assertThat(order.rawDetailJson()).contains("ORDER-ONLY-1"));
        assertThat(collected.batch().shipments()).isEmpty();
        assertThat(collected.batch().shipmentLogistics()).isEmpty();
        assertThat(collected.batch().returns()).isEmpty();
        assertThat(collected.batch().financialDocuments()).isEmpty();
        server.verify();
    }

    @Test
    void returnScopeOnlyFetchesReturnsAndDetails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/returns/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.begin").value(0))
                .andExpect(jsonPath("$.step").value(100))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "returnNumber": "RETURN-ONLY-1",
                            "status": "finished",
                            "returnAmount": 3.00,
                            "sourceFields": {"ReturnsSN": "RETURN-ONLY-1"}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/returns/RETURN-ONLY-1/content"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{}"))
                .andRespond(withSuccess("""
                        {
                          "returnNumber": "RETURN-ONLY-1",
                          "sourceFields": {"ReturnsSN": "RETURN-ONLY-1"}
                        }
                        """, MediaType.APPLICATION_JSON));

        HttpDhbOrderSyncClient client = new HttpDhbOrderSyncClient(builder, signer(),
                JsonMapper.builder().build(), base);
        DhbOrderSyncClient.Collected collected = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1, null, null, DhbOrderSyncScope.RETURN));

        assertThat(collected.objectType()).isEqualTo("RETURN");
        assertThat(collected.fetched()).isEqualTo(1);
        assertThat(collected.completedObjects()).containsExactlyInAnyOrder("RETURN", "RETURN_DETAIL");
        assertThat(collected.batch().returns()).singleElement()
                .satisfies(item -> assertThat(item.rawJson()).contains("RETURN-ONLY-1"));
        assertThat(collected.batch().orders()).isEmpty();
        assertThat(collected.batch().shipments()).isEmpty();
        assertThat(collected.batch().shipmentLogistics()).isEmpty();
        assertThat(collected.batch().financialDocuments()).isEmpty();
        server.verify();
    }

    @Test
    void returnDetailBackfillsNestedOrderNumberAndGeneratesDistinctKeysForDuplicateLines() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID + "/returns/query"))
                .andRespond(withSuccess("""
                        {"total":1,"items":[{"returnNumber":"RET-NESTED-1","sourceFields":{}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/returns/RET-NESTED-1/content"))
                .andRespond(withSuccess("""
                        {
                          "returnNumber":"RET-NESTED-1",
                          "sourceFields":{"body":[{"OrdersNum":"ORD-NESTED-1"}]},
                          "lines":[
                            {"productGuid":"G-1","skuNumber":"SKU-1","productName":"商品","quantity":1},
                            {"productGuid":"G-1","skuNumber":"SKU-1","productName":"商品","quantity":1}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        HttpDhbOrderSyncClient client = new HttpDhbOrderSyncClient(builder, signer(),
                JsonMapper.builder().build(), base);
        DhbOrderSyncClient.Collected collected = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1, null, null, DhbOrderSyncScope.RETURN));

        assertThat(collected.batch().returns()).singleElement().satisfies(item -> {
            assertThat(item.orderNo()).isEqualTo("ORD-NESTED-1");
            assertThat(item.lines()).hasSize(2);
            assertThat(item.lines().get(0).sourceLineId())
                    .isNotEqualTo(item.lines().get(1).sourceLineId());
        });
        server.verify();
    }

    @Test
    void rejectsProviderPageThatReportsMoreRowsThanFetched() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";
        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID + "/query"))
                .andRespond(withSuccess("""
                        {"total":2,"items":[{"orderNumber":"ORD-INCOMPLETE-1"}]}
                        """, MediaType.APPLICATION_JSON));

        HttpDhbOrderSyncClient client = new HttpDhbOrderSyncClient(builder, signer(),
                JsonMapper.builder().build(), base);
        assertThatThrownBy(() -> client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(false, 1, null, null, DhbOrderSyncScope.ORDER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxPages");
        server.verify();
    }

    @Test
    void documentScopesOnlyFetchTheirOwnProviderObjects() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "https://integration.test";

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/shipments/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.begin").value(0))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "shipmentNumber": "SHIP-ONLY-1",
                            "orderNumber": "ORD-1",
                            "status": "shipped",
                            "sourceFields": {"ships_num": "SHIP-ONLY-1"}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/shipments/SHIP-ONLY-1/content"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "shipmentNumber": "SHIP-ONLY-1",
                          "sourceFields": {"ships_num": "SHIP-ONLY-1", "list": []}
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID + "/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "orderNumber": "ORD-LOG-1",
                            "status": "shipped",
                            "sourceFields": {"OrderSN": "ORD-LOG-1"}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/ORD-LOG-1/wait-ships"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "orderNumber": "ORD-LOG-1",
                          "shipped": [],
                          "waitStock": [],
                          "sourceFields": {}
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/receipts/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "receiptNumber": "REC-ONLY-1",
                            "amount": 12.50,
                            "sourceFields": {"ReceiptsNum": "REC-ONLY-1", "Amount": "12.50"}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(base + "/api/v1/integration/dhb/orders/" + CONNECTOR_ID
                + "/payments/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "total": 1,
                          "items": [{
                            "paymentNumber": "PAY-ONLY-1",
                            "amount": 2.50,
                            "sourceFields": {"PaymentNum": "PAY-ONLY-1", "Amount": "2.50"}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        HttpDhbOrderSyncClient client = new HttpDhbOrderSyncClient(builder, signer(),
                JsonMapper.builder().build(), base);

        DhbOrderSyncClient.Collected shipment = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1, null, null, DhbOrderSyncScope.SHIPMENT));
        assertThat(shipment.objectType()).isEqualTo("SHIPMENT");
        assertThat(shipment.completedObjects()).containsExactlyInAnyOrder("SHIPMENT", "SHIPMENT_DETAIL");
        assertThat(shipment.batch().shipments()).singleElement()
                .satisfies(item -> assertThat(item.detailIncluded()).isTrue());
        assertThat(shipment.batch().orders()).isEmpty();
        assertThat(shipment.batch().returns()).isEmpty();
        assertThat(shipment.batch().financialDocuments()).isEmpty();

        DhbOrderSyncClient.Collected logistics = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1, null, null, DhbOrderSyncScope.SHIPMENT_LOGISTICS));
        assertThat(logistics.objectType()).isEqualTo("SHIPMENT_LOGISTICS");
        assertThat(logistics.completedObjects()).containsExactly("SHIPMENT_LOGISTICS");
        assertThat(logistics.batch().shipmentLogistics()).singleElement()
                .extracting(DhbOrderImportBatch.ShipmentLogisticsItem::orderNo)
                .isEqualTo("ORD-LOG-1");
        assertThat(logistics.batch().orders()).isEmpty();

        DhbOrderSyncClient.Collected receipt = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1, null, null, DhbOrderSyncScope.RECEIPT));
        assertThat(receipt.objectType()).isEqualTo("RECEIPT");
        assertThat(receipt.completedObjects()).containsExactly("RECEIPT");
        assertThat(receipt.batch().financialDocuments()).singleElement()
                .extracting(DhbOrderImportBatch.FinancialItem::documentType)
                .isEqualTo("RECEIPT");

        DhbOrderSyncClient.Collected payment = client.collect(caller(), CONNECTOR_ID,
                new DhbOrderSyncCommand(true, 1, null, null, DhbOrderSyncScope.PAYMENT));
        assertThat(payment.objectType()).isEqualTo("PAYMENT");
        assertThat(payment.completedObjects()).containsExactly("PAYMENT");
        assertThat(payment.batch().financialDocuments()).singleElement()
                .extracting(DhbOrderImportBatch.FinancialItem::documentType)
                .isEqualTo("PAYMENT");
        assertThat(payment.batch().orders()).isEmpty();
        assertThat(payment.batch().returns()).isEmpty();
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
