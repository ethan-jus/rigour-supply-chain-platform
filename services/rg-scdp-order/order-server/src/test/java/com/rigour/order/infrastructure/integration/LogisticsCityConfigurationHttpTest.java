package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.*;

class LogisticsCityConfigurationHttpTest {
    static final String ACTION = "order:logistics-cost:write";
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    HttpOrderLogisticsReferences refs;

    @BeforeEach
    void setup() {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        user,
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(ACTION)));
        var keys = new ContextTrustProperties();
        keys.setActiveKeyId("test");
        keys.setKeysBase64(Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
        refs =
                new HttpOrderLogisticsReferences(
                        builder,
                        new TrustedContextSigner(keys),
                        "http://erp.test",
                        "http://settings.test");
        refs.crmAddress("http://crm.test");
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    void directory() {
        server.expect(requestTo("http://crm.test/api/v1/crm/cost-city-references?action=" + ACTION))
                .andRespond(
                        withSuccess(
                                "{\"code\":\"OK\",\"data\":[{\"code\":\"BJ\",\"name\":\"北京市\"}]}",
                                MediaType.APPLICATION_JSON));
    }

    void check(String code, String response) {
        server.expect(
                        requestTo(
                                "http://crm.test/api/v1/crm/cost-city-references/checks?action="
                                        + ACTION))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(RequestHeaders.TENANT_ID, tenant.toString()))
                .andExpect(header(RequestHeaders.USER_ID, user.toString()))
                .andExpect(content().json("[\"" + code + "\"]"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    @Test
    void dirtyRegionIsConfigurationErrorInsteadOfForbidden() {
        directory();
        check(
                "DIRTY",
                "{\"code\":\"OK\",\"data\":[{\"code\":\"DIRTY\",\"configured\":false,\"allowed\":false,\"issue\":\"业务归属地区“散客”（DIRTY）不是经营城市，请在仓库信息中修改业务归属地区后重新选择\"}]}");
        assertThatThrownBy(() -> refs.city("DIRTY", ACTION))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                            assertThat(e.getMessage()).contains("业务归属地区", "DIRTY", "仓库信息", "重新选择");
                        });
        server.verify();
    }

    @Test
    void validCityOutsideScopeRemainsForbidden() {
        directory();
        check(
                "WH",
                "{\"code\":\"OK\",\"data\":[{\"code\":\"WH\",\"configured\":true,\"allowed\":false}]}");
        assertThatThrownBy(() -> refs.city("WH", ACTION))
                .isInstanceOf(AuthorizationDeniedException.class);
        server.verify();
    }

    @Test
    void allowedCityDoesNotNeedAnAdditionalConfigurationLookup() {
        directory();
        assertThat(refs.city("BJ", ACTION)).isEqualTo("北京市");
        server.verify();
    }

    @Test
    void missingReplyNeverTurnsIntoAConfigurationErrorOrAllowsSaving() {
        directory();
        check("WH", "{\"code\":\"OK\",\"data\":[]}");
        assertThatThrownBy(() -> refs.city("WH", ACTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("核验暂不可用");
        server.verify();
    }

    @Test
    void remotePermissionDenialRemainsForbidden() {
        directory();
        server.expect(
                        requestTo(
                                "http://crm.test/api/v1/crm/cost-city-references/checks?action="
                                        + ACTION))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> refs.city("WH", ACTION))
                .isInstanceOf(AuthorizationDeniedException.class);
        server.verify();
    }
}
