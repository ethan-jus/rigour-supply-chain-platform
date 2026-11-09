package com.rigour.merchant.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

class HttpStorePoiClientTest {
    HttpServer server;HttpStorePoiClient client;TrustedContextSigner signer;
    String body;int status;MockHttpServletRequest request;
    @BeforeEach void start()throws Exception {
        var trust=new ContextTrustProperties();trust.setKeysBase64(Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));
        signer=new TrustedContextSigner(trust);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/integration/store-pois",exchange->{
            request=new MockHttpServletRequest("GET",exchange.getRequestURI().getRawPath());
            request.setQueryString(exchange.getRequestURI().getRawQuery());
            exchange.getRequestHeaders().forEach((key,values)->request.addHeader(key,values.getFirst()));
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();client=new HttpStorePoiClient(RestClient.builder(),signer,"http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    @Test void signsServiceIdentityAndReadsRealHttpJsonContract() {
        status=200;body="""
          {"code":"OK","data":[{"id":"B001","name":"球馆","address":"地址","publicPhone":null,"typeCode":"080113","areaCode":"330105","longitude":120.1,"latitude":30.2}]}
          """;
        assertThat(client.search(UUID.randomUUID().toString(),"球馆","330105")).singleElement().satisfies(p->assertThat(p.areaCode()).isEqualTo("330105"));
        assertThat(signer.verify(request)).isTrue();
        assertThat(request.getHeader(RequestHeaders.PERMISSIONS)).isEqualTo("integration:amap:read");
    }
    @Test void preservesOnlyKnownProviderReasonWithoutRawRemoteMessage() {
        status=503;body="""
          {"code":"SERVICE_UNAVAILABLE","message":"private secret","details":[{"field":"amap","reason":"AMAP_IP_NOT_ALLOWED","message":"remote secret"}]}
          """;
        assertThatThrownBy(()->client.detail(UUID.randomUUID().toString(),"B001")).isInstanceOfSatisfying(BusinessException.class,e->{
            assertThat(e.getMessage()).contains("10005").doesNotContain("secret");
            assertThat(e.getDetails()).singleElement().satisfies(d->assertThat(d.reason()).isEqualTo("AMAP_IP_NOT_ALLOWED"));
        });
    }
    @Test void unknownUpstreamFailureNeverLeaksRawDiagnostics() {
        status=500;body="private upstream secret";
        assertThatThrownBy(()->client.detail(UUID.randomUUID().toString(),"B001")).isInstanceOf(BusinessException.class).hasMessageNotContaining("secret");
    }
}
