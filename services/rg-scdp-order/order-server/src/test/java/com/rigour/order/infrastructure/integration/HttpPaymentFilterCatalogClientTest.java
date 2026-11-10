package com.rigour.order.infrastructure.integration;

import com.rigour.shared.context.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

class HttpPaymentFilterCatalogClientTest {
    @Test void onlyNamesCodesAndVariantsLeaveTheCatalogBoundary() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var tenant = UUID.randomUUID();
        var requests = new ArrayList<Map<String,String>>();
        server.createContext("/api/v1/erp/", exchange -> {
            requests.add(Map.of("tenant",exchange.getRequestHeaders().getFirst(RequestHeaders.TENANT_ID),
                    "permission",exchange.getRequestHeaders().getFirst(RequestHeaders.PERMISSIONS),
                    "signature",exchange.getRequestHeaders().getFirst(RequestHeaders.CONTEXT_SIGNATURE)));
            String items = exchange.getRequestURI().getPath().endsWith("product-categories")
                    ? "[{\"id\":1,\"parentId\":null,\"categoryName\":\"食品\"}]"
                    : "[{\"id\":10,\"productCode\":\"P10\",\"productName\":\"商品\",\"categoryId\":1,\"purchasePrice\":99,\"variants\":[{\"id\":20,\"specificationSnapshot\":\"规格\",\"purchasePrice\":88}]}]";
            byte[] body = ("{\"code\":\"OK\",\"data\":{\"total\":1,\"begin\":0,\"step\":200,\"items\":"+items+"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var properties = new ContextTrustProperties();
            properties.setActiveKeyId("test");
            properties.setKeysBase64(Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
            var client = new HttpPaymentFilterCatalogClient(RestClient.builder(),new TrustedContextSigner(properties),
                    new SimpleClientHttpRequestFactory(),"http://127.0.0.1:"+server.getAddress().getPort());
            var actor = new CallerIdentity("SERVICE",UUID.randomUUID(),tenant,null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("erp:product:read"));
            var catalog = client.catalog(actor);
            assertThat(catalog.products().getFirst().variants().getFirst().id()).isEqualTo("20");
            assertThat(catalog.products().getFirst().categoryId()).isEqualTo("1");
            assertThat(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(catalog))
                    .doesNotContain("purchasePrice");
            assertThat(requests).hasSize(2).allSatisfy(r -> {
                assertThat(r.get("tenant")).isEqualTo(tenant.toString());
                assertThat(r.get("permission")).isEqualTo("erp:product:read");
                assertThat(r.get("signature")).isNotBlank();
            });
        } finally { server.stop(0); }
    }
}
