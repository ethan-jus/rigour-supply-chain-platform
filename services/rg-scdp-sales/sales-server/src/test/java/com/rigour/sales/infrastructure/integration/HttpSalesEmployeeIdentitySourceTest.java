package com.rigour.sales.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class HttpSalesEmployeeIdentitySourceTest {
    HttpServer server;
    HttpSalesEmployeeIdentitySource source;
    TrustedContextSigner signer;
    UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    CallerIdentity caller = new CallerIdentity("TENANT", user, tenant, user, null,
            UUID.randomUUID(), 1, 2, 3, Set.of(), Set.of("sales:context:read", "sales:visit:own:write"));
    String code = "EMP-01", responseCode = "EMP-01";
    UUID responseTenant = tenant;
    boolean usable = true, allowed = true, writePermission = true;
    int iamStatus = 200, hrStatus = 200, hrCalls, validIamSignatures, validHrSignatures;

    @BeforeEach void start() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        signer = new TrustedContextSigner(trust);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/iam/supply/authorization", ex -> {
            var request = request(ex);
            if (signer.verify(request) && user.toString().equals(request.getHeader(RequestHeaders.USER_ID))
                    && tenant.toString().equals(request.getHeader(RequestHeaders.TENANT_ID))
                    && caller.sessionId().toString().equals(request.getHeader(RequestHeaders.SESSION_ID))
                    && "TENANT".equals(request.getHeader(RequestHeaders.PRINCIPAL_SCOPE))) validIamSignatures++;
            send(ex, iamStatus, """
                    {"mode":"ACTIVE","tenantId":"%s","userId":"%s","employeeCode":%s,
                     "applicationVersion":1,"memberVersion":1,"employeeRevision":3,"organizationVersion":4,
                     "permissions":["sales:context:read"%s],"action":"sales:context:read",
                     "functionAllowed":%s,"clauses":[],"regionLimit":null,"warehouseLimit":null}
                    """.formatted(responseTenant, user, code == null ? "null" : "\"" + code + "\"",
                            writePermission ? ",\"sales:visit:own:write\"" : "", allowed));
        });
        server.createContext("/api/v1/hr/employee-identities/", ex -> {
            hrCalls++;
            var request = request(ex);
            if (signer.verify(request) && tenant.toString().equals(request.getHeader(RequestHeaders.TENANT_ID))
                    && "SERVICE".equals(request.getHeader(RequestHeaders.PRINCIPAL_SCOPE))
                    && "hr:employee:identity-read".equals(request.getHeader(RequestHeaders.PERMISSIONS))
                    && request.getHeader(RequestHeaders.USER_ID) == null) validHrSignatures++;
            send(ex, hrStatus, """
                    {"code":"OK","data":{"id":9007199254740993,"employeeCode":"%s","employeeName":"测试员工",
                     "employmentStatus":"ACTIVE","departmentAncestorIds":[],"employeeRevision":3,
                     "organizationVersion":4,"accessVersion":5,"usable":%s}}
                    """.formatted(responseCode, usable));
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        source = new HttpSalesEmployeeIdentitySource(signer, RestClient.builder(), base, base);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void preservesOriginalIamCallerAndUsesOnlyIdentityReadServicePermissionForHr() {
        var employee = source.current(caller);
        assertThat(employee.id()).isEqualTo(9007199254740993L);
        assertThat(employee.code()).isEqualTo("EMP-01");
        assertThat(validIamSignatures).isEqualTo(1);
        assertThat(validHrSignatures).isEqualTo(1);
    }
    @Test void administratorWithoutEmployeeCannotFabricateAnIdentity() {
        code = null;
        fails(ErrorCode.SALES_IDENTITY_UNBOUND);
        assertThat(hrCalls).isZero();
    }
    @Test void hrDisableTakesEffectOnNextRequest() {
        source.current(caller);
        usable = false;
        fails(ErrorCode.SALES_PROFILE_INACTIVE);
        assertThat(hrCalls).isEqualTo(2);
    }
    @Test void revokedWritePermissionIsNotAuthorizedByRemainingContextPermission() {
        source.current(caller);
        writePermission = false;
        fails(ErrorCode.FORBIDDEN);
        assertThat(hrCalls).isEqualTo(1);
    }
    @Test void deniedIamAndForeignTenantResponseFailClosed() {
        allowed = false; fails(ErrorCode.FORBIDDEN);
        allowed = true; responseTenant = UUID.randomUUID(); unavailable();
        assertThat(hrCalls).isZero();
    }
    @Test void upstreamOutageIsNotReportedAsInactiveOrEmpty() {
        iamStatus = 503; unavailable();
        iamStatus = 200; hrStatus = 503; unavailable();
    }
    @Test void wrongHrEmployeeAndMissingEmployeeAreRejected() {
        responseCode = "EMP-OTHER"; unavailable();
        hrStatus = 404; fails(ErrorCode.SALES_IDENTITY_UNBOUND);
    }
    @Test void revokedSessionCannotReadHr() {
        iamStatus = 401; fails(ErrorCode.FORBIDDEN);
        assertThat(hrCalls).isZero();
    }
    private void fails(ErrorCode code) {
        assertThatThrownBy(() -> source.current(caller)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    private void unavailable() {
        assertThatThrownBy(() -> source.current(caller)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }
    private static MockHttpServletRequest request(HttpExchange ex) {
        var req = new MockHttpServletRequest(ex.getRequestMethod(), ex.getRequestURI().getRawPath());
        req.setQueryString(ex.getRequestURI().getRawQuery());
        ex.getRequestHeaders().forEach((k, v) -> req.addHeader(k, v.getFirst()));
        return req;
    }
    private static void send(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes); ex.close();
    }
}
