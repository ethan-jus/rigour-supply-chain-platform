package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

class LogisticsCustomerBatchHttpTest {
    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void multipleCitiesUseOneSignedRequestAndKeepContactFieldsInTheirCorrectPositions() {
        var id = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        id,
                        tenant,
                        id,
                        null,
                        id,
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:logistics-cost:read")));
        var keys = new ContextTrustProperties();
        keys.setActiveKeyId("test");
        keys.setKeysBase64(Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var refs =
                new HttpOrderLogisticsReferences(
                        builder,
                        new TrustedContextSigner(keys),
                        "http://erp.test",
                        "http://settings.test");
        refs.crmAddress("http://crm.test");
        server.expect(
                        requestTo(
                                "http://crm.test/api/v1/crm/cost-customer-references/batch-cities?action=order:logistics-cost:read"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(RequestHeaders.TENANT_ID, tenant.toString()))
                .andRespond(
                        withSuccess(
                                "{\"code\":\"OK\",\"data\":{\"WH\":[{\"id\":\"1\",\"code\":\"CUS1\",\"name\":\"门店\",\"contactName\":\"王先生\",\"phone\":\"13900000000\",\"address\":\"武汉市收货路1号\"}],\"BJ\":[]}}",
                                org.springframework.http.MediaType.APPLICATION_JSON));
        var result =
                refs.ledgerCustomersByCities(
                        Map.of("WH", List.of("CUS1"), "BJ", List.of("CUS2")),
                        "order:logistics-cost:read");
        var row = result.get("WH").getFirst();
        assertThat(row.address()).isEqualTo("武汉市收货路1号");
        assertThat(row.contactName()).isEqualTo("王先生");
        assertThat(row.phone()).isEqualTo("13900000000");
        assertThat(row.cityCode()).isEqualTo("WH");
        assertThat(result.get("BJ")).isEmpty();
        server.verify();
    }

    @Test
    void sameCustomerBatchIsReadOncePerRequestWithAnImmutableKey() {
        var tenant = UUID.randomUUID();
        caller(tenant);
        newRequest();
        var fixture = fixture();
        fixture.server
                .expect(requestTo(url("read")))
                .andExpect(content().json("{\"WH\":[\"C1\",\"C2\"]}"))
                .andRespond(
                        withSuccess(
                                "{\"code\":\"OK\",\"data\":{\"WH\":[]}}",
                                MediaType.APPLICATION_JSON));
        fixture.server
                .expect(requestTo(url("read")))
                .andExpect(content().json("{\"WH\":[\"C1\",\"C2\",\"C3\"]}"))
                .andRespond(
                        withSuccess(
                                "{\"code\":\"OK\",\"data\":{\"WH\":[]}}",
                                MediaType.APPLICATION_JSON));
        var codes = new ArrayList<>(List.of("C2", "C1", "C1"));
        var first =
                fixture.refs.ledgerCustomersByCities(
                        Map.of("WH", codes), "order:logistics-cost:read");
        assertThat(
                        fixture.refs.ledgerCustomersByCities(
                                Map.of("WH", List.of("C1", "C2")), "order:logistics-cost:read"))
                .isSameAs(first);
        codes.add("C3");
        fixture.refs.ledgerCustomersByCities(Map.of("WH", codes), "order:logistics-cost:read");
        assertThat(
                        fixture.refs.ledgerCustomersByCities(
                                Map.of("WH", List.of("C2", "C1")), "order:logistics-cost:read"))
                .isSameAs(first);
        assertThatThrownBy(() -> first.put("BJ", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        fixture.server.verify();
    }

    @Test
    void tenantActionCityAndNextRequestRequireFreshSignedReads() {
        var t1 = UUID.randomUUID();
        var t2 = UUID.randomUUID();
        caller(t1);
        newRequest();
        var fixture = fixture();
        expectEmpty(fixture.server, "read", t1);
        expectEmpty(fixture.server, "write", t1);
        expectEmpty(fixture.server, "read", t1);
        expectEmpty(fixture.server, "read", t2);
        expectEmpty(fixture.server, "read", t1);
        fixture.refs.ledgerCustomersByCities(
                Map.of("WH", List.of("C1")), "order:logistics-cost:read");
        fixture.refs.ledgerCustomersByCities(
                Map.of("WH", List.of("C1")), "order:logistics-cost:write");
        fixture.refs.ledgerCustomersByCities(
                Map.of("BJ", List.of("C1")), "order:logistics-cost:read");
        caller(t2);
        fixture.refs.ledgerCustomersByCities(
                Map.of("WH", List.of("C1")), "order:logistics-cost:read");
        caller(t1);
        newRequest();
        fixture.refs.ledgerCustomersByCities(
                Map.of("WH", List.of("C1")), "order:logistics-cost:read");
        fixture.server.verify();
    }

    @Test
    void failedCustomerReadCanRetryWithoutReusingTheFailure() {
        var tenant = UUID.randomUUID();
        caller(tenant);
        newRequest();
        var fixture = fixture();
        fixture.server.expect(requestTo(url("read"))).andRespond(withServerError());
        expectEmpty(fixture.server, "read", tenant);
        var codes = Map.of("WH", List.of("C1"));
        assertThatThrownBy(
                        () ->
                                fixture.refs.ledgerCustomersByCities(
                                        codes, "order:logistics-cost:read"))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        assertThat(fixture.refs.ledgerCustomersByCities(codes, "order:logistics-cost:read"))
                .isEmpty();
        fixture.server.verify();
    }

    private static String url(String action) {
        return "http://crm.test/api/v1/crm/cost-customer-references/batch-cities?action=order:logistics-cost:"
                + action;
    }

    private static void expectEmpty(MockRestServiceServer server, String action, UUID tenant) {
        server.expect(requestTo(url(action)))
                .andExpect(header(RequestHeaders.TENANT_ID, tenant.toString()))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "test"))
                .andRespond(
                        withSuccess("{\"code\":\"OK\",\"data\":{}}", MediaType.APPLICATION_JSON));
    }

    private static void newRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static void caller(UUID tenant) {
        var id = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        id,
                        tenant,
                        id,
                        null,
                        id,
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:logistics-cost:read", "order:logistics-cost:write")));
    }

    private static Fixture fixture() {
        var keys = new ContextTrustProperties();
        keys.setActiveKeyId("test");
        keys.setKeysBase64(Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var refs =
                new HttpOrderLogisticsReferences(
                        builder,
                        new TrustedContextSigner(keys),
                        "http://erp.test",
                        "http://settings.test");
        refs.crmAddress("http://crm.test");
        return new Fixture(refs, server);
    }

    private record Fixture(HttpOrderLogisticsReferences refs, MockRestServiceServer server) {}
}
