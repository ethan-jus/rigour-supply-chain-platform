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
