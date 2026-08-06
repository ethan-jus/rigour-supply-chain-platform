package com.rigour.integration.infrastructure.dhb;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.integration.application.port.out.DhbClient.CustomerQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderQuery;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.Product;
import com.rigour.integration.application.port.out.DhbClient.ProductQuery;
import com.rigour.integration.infrastructure.config.DhbClientProperties;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class DhbClientAdapterTest {

    private static final DhbClient.Connector CONNECTOR = new DhbClient.Connector(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "https://api.test/erp", "env://DHB_TEST");

    @Test
    void authenticatesWithEnvelopeAndCachesTokenAcrossPages() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"f":"getTokenValue","v":{"SerialNumber":"fixture-account","Password":"fixture-credential"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"f":"getGoodsList","v":{"sKey":"opaque-token","begin":0,"step":1}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rTotal":2,"rData":[{"guid":"g-1","coding":"G-1","name":"商品一","putaway":"T"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"f":"getGoodsList","v":{"sKey":"opaque-token","begin":1,"step":1}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rTotal":2,"rData":[{"guid":"g-2","coding":"G-2","name":"商品二","putaway":"T"}]}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());
        Page<Product> first = client.getProducts(CONNECTOR, ProductQuery.first(1));
        Page<Product> second = client.getProducts(CONNECTOR,
                new ProductQuery(first.nextRequest(), null, null, null));

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(Product::code).containsExactly("G-1");
        assertThat(second.items()).extracting(Product::code).containsExactly("G-2");
        assertThat(second.hasNext()).isFalse();
        server.verify();
    }

    @Test
    void sendsCustomerIncrementalWindowInChinaStandardTime() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getDealersList","v":{"sKey":"opaque-token","begin":0,"step":100,"status":2,"data_type":2,"time_type":"update_date","start_time":"2026-08-01 08:00:00","end_time":"2026-08-01 09:00:00","client_no":"C-1","client_area":12,"type_id":34}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rTotal":0,"rData":[]}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());
        Page<?> page = client.getCustomers(CONNECTOR, new CustomerQuery(
                new DhbClient.PageRequest(0, 100), 2, 2, "update_date",
                new DhbClient.TimeWindow(Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-08-01T01:00:00Z")), "C-1", 12, 34));

        assertThat(page.items()).isEmpty();
        server.verify();
    }

    @Test
    void mapsOrderIncrementalWindowAndSummaryFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getOrderList","v":{"sKey":"opaque-token","begin":0,"step":100,"starttime":"2026-08-01 08:00:00","endtime":"2026-08-01 09:00:00","exceptionStatus":"all","apiStatus":"F","payStatus":"paided","splitType":2}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rTotal":1,"rData":[{"OrderSN":"DH-1","OrderStatus":"stockup","OrderTotal":"12.50","OrderDate":1785542400,"OrderUpdateDate":1785542460,"ClientNO":"C-1","PayStatus":"oblig"}]}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());
        Page<?> page = client.getOrders(CONNECTOR, new OrderQuery(
                new DhbClient.PageRequest(0, 100), null,
                new DhbClient.TimeWindow(Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-08-01T01:00:00Z")), null,
                "all", "F", "paided", 2));

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst()).isInstanceOf(DhbClient.OrderSummary.class);
        DhbClient.OrderSummary order = (DhbClient.OrderSummary) page.items().getFirst();
        assertThat(order.orderNumber()).isEqualTo("DH-1");
        assertThat(order.amount()).isEqualByComparingTo("12.50");
        server.verify();
    }

    @Test
    void mapsShipsListPaginationFiltersAndShipsContentDetail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getShipsList","v":{"sKey":"opaque-token","page":1,"page_size":100,
                          "status":"receivedin","is_api":"F,T","type_id":"10",
                          "create_date_egt":"2026-08-01 08:00:00","create_date_elt":"2026-08-01 09:00:00",
                          "update_date_egt":"2026-08-02 08:00:00","update_date_elt":"2026-08-02 09:00:00",
                          "client_num":"C-1","stock_id":"12","stock_num":"WH-1"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rData":{"page_size":100,"page":1,
                          "total_page":1,"total":1,"data":[{
                            "ships_id":7,"client_num":"C-1","client_name":"客户一","client_guid":"CG-1",
                            "type_id":10,"type_name":"销售出库","stock_num":"WH-1","stock_name":"中心仓",
                            "stock_guid":"WG-1","ships_num":"FH-1","orders_num":"DH-1",
                            "status":"receivedin","status_name":"待收货","ships_date":"2026-08-02 10:00:00",
                            "logistics_name":"顺丰","express_num":"SF-1","remark":"备注",
                            "update_date":"2026-08-02 10:01:00","create_date":"2026-08-02 09:59:00"}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getShipsContent","v":{"sKey":"opaque-token","ships_num":"FH-1"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rData":{
                          "ships_num":"FH-1","orders_num":"DH-1","stock_num":"WH-1",
                          "list":[{"ships_list_id":8,"goods_id":9,"goods_guid":"GG-1",
                            "goods_num":"G-1","goods_name":"商品一","options_goods_num":"SKU-1",
                            "ships_number":"2.0000","orders_list_info":{"actual_amount":"25.00",
                              "order_units_price":"12.50","order_units_name":"件"}}]}}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());
        Page<?> page = client.getShipments(CONNECTOR, new DhbClient.ShipmentQuery(
                new DhbClient.PageRequest(0, 100), "receivedin", "F,T", "10",
                new DhbClient.TimeWindow(Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-08-01T01:00:00Z")),
                new DhbClient.TimeWindow(Instant.parse("2026-08-02T00:00:00Z"),
                        Instant.parse("2026-08-02T01:00:00Z")),
                "C-1", "12", "WH-1"));
        DhbClient.Shipment shipment = (DhbClient.Shipment) page.items().getFirst();
        assertThat(page.total()).isEqualTo(1);
        assertThat(shipment.shipmentNumber()).isEqualTo("FH-1");
        assertThat(shipment.orderNumber()).isEqualTo("DH-1");

        DhbClient.ShipmentDetail detail = client.getShipmentContent(CONNECTOR, "FH-1");
        assertThat(detail.shipmentNumber()).isEqualTo("FH-1");
        assertThat(detail.attributes()).containsKey("list");
        server.verify();
    }

    @Test
    void mapsGetWaitShipsShippedAndWaitStockPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getWaitShips","v":{"sKey":"opaque-token","orders_num":"DH-1"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"信息获取成功","rData":{
                          "shipped":[{"ships_id":"S-1","ships_num":"FH-1","status":"receivedin",
                            "logistics_name":"顺丰","logistics_code":"shunfeng","express_num":"SF-1",
                            "ships_date":"2026-08-05 10:00:00","ships_time":"2026-08-05 10:05:00",
                            "stock_num":"WH-1","stock_name":"中心仓",
                            "list":[{"ships_list_id":"SL-1","orders_list_id":"OL-1","goods_id":"G-1",
                              "options_goods_num":"SKU-1","list_type":"buy","goods_num":"P-1",
                              "goods_name":"商品一","goods_options":"规格一","base_units":"件",
                              "container_units":"箱","conversion_number":"12.0000","orders_units":"base_units",
                              "remark":"","ships_number":"2.0000"}]}],
                          "wait_stock":[{"orders_list_id":"OL-2","goods_id":"G-2","options_goods_num":"SKU-2",
                            "list_type":"buy","goods_num":"P-2","goods_name":"商品二","base_units":"件",
                            "container_units":"箱","conversion_number":"12.0000","stock_num":"WH-1",
                            "stock_name":"中心仓","orders_number":"3.0000","stock_number":"1.0000",
                            "real_number":"10.0000","wait_stock_number":2,"remark":""}]}}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());
        DhbClient.WaitShips result = client.getWaitShips(CONNECTOR, "DH-1");

        assertThat(result.shipped()).hasSize(1);
        assertThat(result.shipped().getFirst().shipmentNo()).isEqualTo("FH-1");
        assertThat(result.shipped().getFirst().trackingNo()).isEqualTo("SF-1");
        assertThat(result.shipped().getFirst().lines().getFirst().quantity()).isEqualByComparingTo("2.0000");
        assertThat(result.waitStock()).hasSize(1);
        assertThat(result.waitStock().getFirst().waitQuantity()).isEqualByComparingTo("2");
        server.verify();
    }

    @Test
    void mapsReturnsListAndContentPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getReturnsList","v":{"sKey":"opaque-token","begin":0,"step":100,
                          "status":"shipped,finished","isApi":"F","starttime":"2026-08-01 08:00:00",
                          "endtime":"2026-08-01 09:00:00","updateGe":"2026-08-02 08:00:00",
                          "updateLe":"2026-08-02 09:00:00","stock_id":"12","stock_num":"WH-1"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rTotal":1,"rData":[{
                          "ReturnsSN":"TH-1","ReturnsStatus":"shipped","ReturnsTotal":"20.00",
                          "ReturnsDiscountTotal":"18.00","ReturnsDate":"2026-08-01 10:00:00",
                          "ReturnsUpdateDate":"2026-08-02 10:00:00","ClientNum":"C-1",
                          "ReturnsSendCompany":"顺丰","ReturnsSendNo":"SF-1"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getReturnsContent","v":{"sKey":"opaque-token","returnsSn":"TH-1"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rData":{
                          "ReturnsSN":"TH-1","ReturnsStatus":"finished","OrdersNum":"DH-1",
                          "body":[{"Guid":"G-1","TrueGuid":"TG-1","OptionsGoodsNum":"SKU-1",
                            "Coding":"P-1","Name":"商品一","ReturnsNumber":"2.0000",
                            "ReturnsConfirmNumber":"1.0000","ReturnsPrice":"10.00",
                            "ReturnsConfirmPrice":"9.00","ReturnsUnitsName":"件",
                            "Stock":{"StockId":"12","StockName":"中心仓","StockGuid":"WG-1"}}]}}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());
        DhbClient.Page<DhbClient.ReturnSummary> page = client.getReturns(CONNECTOR,
                new DhbClient.ReturnQuery(new DhbClient.PageRequest(0, 100), "shipped,finished", "F",
                        new DhbClient.TimeWindow(Instant.parse("2026-08-01T00:00:00Z"),
                                Instant.parse("2026-08-01T01:00:00Z")),
                        new DhbClient.TimeWindow(Instant.parse("2026-08-02T00:00:00Z"),
                                Instant.parse("2026-08-02T01:00:00Z")), "12", "WH-1"));
        DhbClient.ReturnSummary summary = page.items().getFirst();
        assertThat(summary.returnNumber()).isEqualTo("TH-1");
        assertThat(summary.returnAmount()).isEqualByComparingTo("20.00");

        DhbClient.ReturnDetail detail = client.getReturnContent(CONNECTOR, "TH-1");
        assertThat(detail.summary().orderNumber()).isEqualTo("DH-1");
        assertThat(detail.lines()).hasSize(1);
        assertThat(detail.lines().getFirst().warehouseNumber()).isEqualTo("12");
        server.verify();
    }

    @Test
    void mapsReceiptsAndPaymentsWithTheirOfficialQueryParameters() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getReceiptsList","v":{"sKey":"opaque-token","orderSn":"DH-1",
                          "begin":0,"step":100,"starttime":"2026-08-01 08:00:00",
                          "endtime":"2026-08-01 09:00:00","updateDateGe":"2026-08-02 08:00:00",
                          "status":"pend_receipted"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rTotal":1,"rData":[{
                          "ReceiptsNum":"FR-1","OrdersNum":"DH-1","ClientNum":"C-1",
                          "IncexpId":"13","TypeId":"Offline","Amount":"88.00",
                          "ReceiptsDate":"2026-08-01","CreateDate":"2026-08-01 10:00:00",
                          "UpdateDate":"2026-08-02 10:00:00","SerialNumber":"SN-1"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getPaymentList","v":{"sKey":"opaque-token","orderSn":"DH-1",
                          "begin":0,"step":100,"starttime":"2026-08-01 08:00:00",
                          "endtime":"2026-08-01 09:00:00","status":"pend_receipted"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rTotal":1,"rData":[{
                          "PaymentNum":"FP-1","ReceiptsNum":"FR-1","OrdersNum":"DH-1",
                          "ClientNum":"C-1","IncexpId":"5","TypeId":"Deposit",
                          "Amount":"10.00","ReceiptsDate":"2026-08-01",
                          "CreateDate":"2026-08-01 11:00:00","SerialNumber":"SN-2"}]}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());
        DhbClient.TimeWindow window = new DhbClient.TimeWindow(Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T01:00:00Z"));
        DhbClient.Page<DhbClient.Receipt> receipts = client.getReceipts(CONNECTOR,
                new DhbClient.ReceiptQuery(new DhbClient.PageRequest(0, 100), "DH-1", window,
                        Instant.parse("2026-08-02T00:00:00Z"), "pend_receipted"));
        DhbClient.Page<DhbClient.Payment> payments = client.getPayments(CONNECTOR,
                new DhbClient.PaymentQuery(new DhbClient.PageRequest(0, 100), "DH-1", window,
                        "pend_receipted"));

        assertThat(receipts.items().getFirst().receiptNumber()).isEqualTo("FR-1");
        assertThat(receipts.items().getFirst().amount()).isEqualByComparingTo("88.00");
        assertThat(receipts.items().getFirst().transactionAt())
                .isEqualTo(Instant.parse("2026-07-31T16:00:00Z"));
        assertThat(payments.items().getFirst().paymentNumber()).isEqualTo("FP-1");
        assertThat(payments.items().getFirst().receiptNumber()).isEqualTo("FR-1");
        server.verify();
    }

    @Test
    void doesNotCallProviderWhenSecretReferenceIsEmpty() {
        DhbClientAdapter client = new DhbClientAdapter(RestClient.builder().build(),
                ref -> { throw new AssertionError("secret resolver must not be called"); }, properties());
        ConnectionTestResult result = client.testConnection(new DhbClient.Connector(
                CONNECTOR.tenantId(), CONNECTOR.connectorId(), CONNECTOR.baseUrl(), ""));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DHB_SECRET_NOT_CONFIGURED");
    }

    @Test
    void resolvesProcessEnvironmentCredentialsWithoutPuttingThemInConnector() {
        EnvDhbSecretResolver resolver = new EnvDhbSecretResolver(
                key -> switch (key) {
                    case "RIGOUR_DHB_DEV_SERIAL_NUMBER" -> "fixture-account";
                    case "RIGOUR_DHB_DEV_PASSWORD" -> "fixture-credential";
                    default -> null;
                });

        DhbSecretResolver.Credentials credentials = resolver.resolve("env://RIGOUR_DHB_DEV");

        assertThat(credentials.serialNumber()).isEqualTo("fixture-account");
        assertThat(credentials.password()).isEqualTo("fixture-credential");
    }

    @Test
    void doesNotRetryProviderBusinessErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":9001,"message":"参数错误","rData":[]}
                """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());
        assertThatThrownBy(() -> client.getProducts(CONNECTOR, ProductQuery.first(100)))
                .isInstanceOf(DhbClientException.class)
                .extracting(Throwable::getMessage)
                .isEqualTo("参数错误");
        server.verify();
    }

    @Test
    void refreshesTokenOnceWhenProviderReturnsOfficial203() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"old-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getGoodsList","v":{"sKey":"old-token","begin":0,"step":100}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":203,"message":"sKey不存在","rData":[]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"new-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getGoodsList","v":{"sKey":"new-token","begin":0,"step":100}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"message":"success","rTotal":0,"rData":[]}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties());

        assertThat(client.getProducts(CONNECTOR, DhbClient.ProductQuery.first(100)).items()).isEmpty();
        server.verify();
    }

    @Test
    void retriesTransientHttpFailureWithBackoff() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rData":{"token":"opaque-token","expires_in":3600}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp")).andRespond(withServerError());
        server.expect(requestTo("https://api.test/erp")).andRespond(withSuccess("""
                {"rStatus":100,"message":"success","rTotal":0,"rData":[]}
                """, MediaType.APPLICATION_JSON));

        DhbClientProperties properties = properties();
        properties.setMaxAttempts(2);
        properties.setInitialBackoff(java.time.Duration.ofMillis(1));
        properties.setMaxBackoff(java.time.Duration.ofMillis(2));
        DhbClientAdapter client = new DhbClientAdapter(builder.build(),
                ref -> new DhbSecretResolver.Credentials("fixture-account", "fixture-credential"), properties);

        assertThat(client.getProducts(CONNECTOR, ProductQuery.first(100)).items()).isEmpty();
        server.verify();
    }

    private static DhbClientProperties properties() {
        DhbClientProperties properties = new DhbClientProperties();
        properties.setMaxAttempts(3);
        properties.setRequestsPerSecond(1000);
        properties.setRateLimitBurst(10);
        properties.setTokenSafetyWindow(java.time.Duration.ofSeconds(1));
        return properties;
    }
}
