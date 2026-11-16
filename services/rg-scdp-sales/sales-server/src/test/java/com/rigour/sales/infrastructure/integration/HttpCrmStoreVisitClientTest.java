package com.rigour.sales.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import com.rigour.merchant.api.v1.model.StoreWorkModels.CheckIn;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

class HttpCrmStoreVisitClientTest {
    HttpServer server; HttpCrmStoreVisitClient client; int status; String body;
    @BeforeEach void setup() throws Exception {
        var trust=new ContextTrustProperties();trust.setKeysBase64(Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/crm/stores/100/visits",exchange->{
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        client=new HttpCrmStoreVisitClient(RestClient.builder(),new TrustedContextSigner(trust),"http://127.0.0.1:"+server.getAddress().getPort());
        UUID user=UUID.randomUUID();var setter=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);setter.setAccessible(true);
        setter.invoke(null,new CallerIdentity("TENANT",user,UUID.randomUUID(),user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of("crm:store:visit-write")));
    }
    @AfterEach void clear() throws Exception {server.stop(0);var clear=AuthorizationContext.class.getDeclaredMethod("clear");clear.setAccessible(true);clear.invoke(null);}
    @Test void distanceRejectionRetainsStableCodeInsteadOfBecomingServerError(){
        status=409;body="{\"code\":\"CRM_STORE_VISIT_OUTSIDE_RADIUS\",\"message\":\"private upstream diagnostic\"}";
        assertThatThrownBy(()->client.checkIn("100",new CheckIn("request-123456789","200",null,null,"GCJ02",null)))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CRM_STORE_VISIT_OUTSIDE_RADIUS))
            .hasMessageNotContaining("private");
    }
    @ParameterizedTest @CsvSource({"400,BAD_REQUEST","403,FORBIDDEN","404,NOT_FOUND","409,CONFLICT"})
    void otherRejectionsKeepStatusWithoutLeakingResponse(int httpStatus,ErrorCode expected){
        status=httpStatus;body="private upstream diagnostic";
        assertThatThrownBy(()->client.context("100","300"))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(expected))
            .hasMessageNotContaining("private");
    }
    @Test void personalQueryForwardsEncodedFiltersAndReturnsCrmAuthorityWithoutSaving() {
        var seen=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/api/v1/crm/store-visits/me",exchange->{
            seen.set(exchange.getRequestURI().getRawQuery());
            var bytes="{\"code\":\"OK\",\"data\":{\"items\":[],\"total\":0,\"summary\":{\"totalVisitCount\":0,\"effectiveVisitCount\":0,\"firstVisitCount\":0},\"active\":null}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        var result=client.ownVisits(java.time.LocalDate.parse("2026-11-01"),java.time.LocalDate.parse("2026-11-12"),"all","SUBMITTED","台球 & 科华",2,20);
        assertThat(result.total()).isZero();assertThat(result.summary().effectiveVisitCount()).isZero();
        assertThat(seen.get()).contains("from=2026-11-01","status=SUBMITTED","page=2","%26").doesNotContain("employeeId=");
    }
    @Test void missingCrmReceiptIsAnErrorNotAnEmptySuccessfulList() {
        server.createContext("/api/v1/crm/store-visits/me",exchange->{
            var bytes="{\"code\":\"OK\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        assertThatThrownBy(()->client.ownVisits(java.time.LocalDate.now(),java.time.LocalDate.now(),"all","ALL",null,1,20)).isInstanceOf(com.rigour.shared.core.exception.StateConflictException.class);
    }

}
