package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.shared.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpHrEmployeeDisplayClientTest {
    @Test
    void expandsDepartmentsUsingEmployeeReadEndpointAndTenantSignature() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        UUID tenant = UUID.randomUUID();
        server.expect(requestTo("https://hr.test/api/v1/hr/employee-departments"))
                .andExpect(header(RequestHeaders.TENANT_ID, tenant.toString()))
                .andExpect(header(RequestHeaders.PERMISSIONS, "hr:employee:read"))
                .andRespond(withSuccess("""
                        {"code":"OK","data":[{"id":1,"parentId":null},{"id":2,"parentId":1},
                        {"id":3,"parentId":2},{"id":4,"parentId":null}]}
                        """, MediaType.APPLICATION_JSON));
        var properties = new ContextTrustProperties();
        properties.setActiveKeyId("v1");
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var client = new HttpHrEmployeeDisplayClient(builder, new TrustedContextSigner(properties), "https://hr.test");
        var caller = new CallerIdentity("SERVICE", UUID.randomUUID(), tenant, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("hr:employee:read"));
        assertThat(client.departmentIdsInScope(caller, 1L, true)).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(client.departmentIdsInScope(caller, 1L, false)).containsExactly(1L);
        server.verify();
    }
}
