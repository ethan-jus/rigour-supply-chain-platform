package com.rigour.analytics.infrastructure.integration;

import com.rigour.shared.context.*;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpBiOrderRepairEvidenceSourceTest {
    private final RestClient.Builder builder=RestClient.builder();
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final UUID user=UUID.randomUUID(),tenant=UUID.randomUUID();
    private final CallerIdentity actor=new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,
            Set.of("SALES"),Set.of("order:read"));
    private HttpBiOrderRepairEvidenceSource source() {
        var props=new ContextTrustProperties();
        props.setKeysBase64(Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));
        return new HttpBiOrderRepairEvidenceSource(builder,new TrustedContextSigner(props),"http://order.test");
    }
    @Test void preservesActualTenantRoleAndReadPermissionWithoutEscalation() {
        var source=source();
        server.expect(requestTo("http://order.test/api/v1/orders/sales/product-repair-evidence?afterLineId=0&limit=20000"))
                .andExpect(method(HttpMethod.GET)).andExpect(header(RequestHeaders.PRINCIPAL_SCOPE,"TENANT"))
                .andExpect(header(RequestHeaders.ROLES,"SALES")).andExpect(header(RequestHeaders.PERMISSIONS,"order:read"))
                .andExpect(header(RequestHeaders.USER_ID,user.toString())).andExpect(header(RequestHeaders.TENANT_ID,tenant.toString()))
                .andRespond(withSuccess("{\"code\":\"OK\",\"data\":{\"items\":[],\"hasMore\":false}}",MediaType.APPLICATION_JSON));
        assertThat(source.current(actor)).isEmpty(); server.verify();
    }
    @Test void truncatedResponseIsNotTreatedAsCompleteEvidence() {
        var source=source();
        server.expect(anything()).andRespond(withSuccess("{\"code\":\"OK\",\"data\":{\"items\":[],\"hasMore\":true}}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->source.current(actor)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
