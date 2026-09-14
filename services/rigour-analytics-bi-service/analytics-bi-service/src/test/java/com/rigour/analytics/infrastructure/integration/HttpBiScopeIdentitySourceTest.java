package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.api.v1.model.BiScopeSyncCommand;
import com.rigour.shared.context.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 使用真实 HTTP 契约模拟上游，防止零客户、姓名相同或跨租户时错误关联。 */
class HttpBiScopeIdentitySourceTest {
    private static final UUID TENANT = UUID.randomUUID(), USER = UUID.randomUUID(), POLICY = UUID.randomUUID();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpBiScopeIdentitySource source = new HttpBiScopeIdentitySource(builder, signer(), "http://iam.test", "http://hr.test", "http://crm.test");
    private final CallerIdentity actor = new CallerIdentity("TENANT", USER, TENANT, USER, null, UUID.randomUUID(), 1, 1, 1,
            Set.of("assigned-role"), Set.of("analytics:dashboard:read"));
    private final BiScopeSyncCommand command = new BiScopeSyncCommand(USER, List.of(POLICY), List.of("BJ"));
    @BeforeEach void setup() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer test-only-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @ParameterizedTest @ValueSource(strings = {"SELF", "MY_CITY", "MY_REGION"})
    void zeroCustomersIsValidForAnySupportedScopeWithExactIamHrIdentity(String type) {
        iam(type, "[]");
        directory("hr.test", "/api/v1/hr/employees", Map.of("employeeCode", "E1"))
                .andRespond(withSuccess("{\"code\":\"OK\",\"data\":{\"total\":1,\"items\":[{\"id\":\"hr-1\",\"employeeCode\":\"E1\",\"employmentStatus\":\"ACTIVE\"}]}}", MediaType.APPLICATION_JSON));
        emptyCustomersAndCity("E1");
        var verified = source.verify(actor, command);
        assertThat(verified.employeeCode()).isEqualTo("E1");
        assertThat(verified.ownerStaffCode()).isEqualTo("E1");
        assertThat(verified.hrEmployeeRef()).isEqualTo("employee:hr-1");
        assertThat(verified.crmEmployeeRef()).isEqualTo("crm:v1:ownerEmployeeCode-contract");
        assertThat(verified.grants()).singleElement().satisfies(grant -> {
            assertThat(grant.scopeType()).isEqualTo(type);
            assertThat(grant.regionCode()).isEqualTo("BJ");
        });
        server.verify();
    }
    @Test void mismatchedTokenTenantFailsBeforeReadingDirectory() {
        server.expect(requestTo("http://iam.test/api/v1/me"))
                .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"tenantId\":\"" + UUID.randomUUID() + "\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.verify(actor, command)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
    @Test void matchingNameOrNoHrIdentityDoesNotMakeAnIdentity() {
        iam("SELF", "[]");
        directory("hr.test", "/api/v1/hr/employees", Map.of("employeeCode", "E1"))
                .andRespond(withSuccess("{\"total\":0,\"items\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.verify(actor, command)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
    @Test void hrMustReturnExactEmployeeCodeNotFuzzyNameMatch() {
        iam("SELF", "[]");
        directory("hr.test", "/api/v1/hr/employees", Map.of("employeeCode", "E1"))
                .andRespond(withSuccess("{\"total\":1,\"items\":[{\"id\":\"hr-2\",\"employeeCode\":\"E2\",\"employmentStatus\":\"ACTIVE\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.verify(actor, command)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
    @Test void historicalDifferentCodesOnlyResolveUsingExactSharedExternalIds() {
        iam("SELF", "[{\"sourceSystem\":\"DINGHUOBAO\",\"sourceTenantKey\":\"tenant-source\",\"sourceEmployeeId\":\"staff-source\"}]");
        directory("hr.test", "/api/v1/hr/employees", Map.of("employeeCode", "E1"))
                .andRespond(withSuccess("{\"total\":0,\"items\":[]}", MediaType.APPLICATION_JSON));
        directory("hr.test", "/internal/v1/hr/employees/source-resolve", Map.of())
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(request -> {
                    var json = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertThat(json).contains("\"sourceEmployeeIds\":[\"staff-source\"]", "\"employeeNames\":[]");
                }).andRespond(withSuccess("{\"code\":\"OK\",\"data\":[{\"employeeId\":\"hr-9\",\"sourceSystem\":\"DINGHUOBAO\",\"sourceTenantKey\":\"tenant-source\",\"sourceEmployeeId\":\"staff-source\"}]}", MediaType.APPLICATION_JSON));
        directory("hr.test", "/api/v1/hr/employees/hr-9", Map.of())
                .andRespond(withSuccess("{\"id\":\"hr-9\",\"employeeCode\":\"HR9\",\"employmentStatus\":\"ACTIVE\"}", MediaType.APPLICATION_JSON));
        emptyCustomersAndCity("HR9");
        assertThat(source.verify(actor, command).ownerStaffCode()).isEqualTo("HR9");
        server.verify();
    }
    @Test void upstreamIgnoringExactCrmOwnerFilterIsRejected() {
        iam("SELF", "[]");
        directory("hr.test", "/api/v1/hr/employees", Map.of("employeeCode", "E1"))
                .andRespond(withSuccess("{\"total\":1,\"items\":[{\"id\":\"hr-1\",\"employeeCode\":\"E1\",\"employmentStatus\":\"ACTIVE\"}]}", MediaType.APPLICATION_JSON));
        directory("crm.test", "/api/v1/crm/internal-customers", Map.of("ownerEmployeeCode", "E1"))
                .andRespond(withSuccess("{\"total\":1,\"items\":[{\"ownerEmployeeCode\":\"OTHER\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> source.verify(actor, command)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
    private void iam(String type, String aliases) {
        server.expect(requestTo("http://iam.test/api/v1/me")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-only-token"))
                .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"tenantId\":\"" + TENANT + "\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://iam.test/api/v1/iam/bi-identity?userId=" + USER))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-only-token"))
                .andRespond(withSuccess("{\"userId\":\"" + USER + "\",\"staffId\":\"iam-staff\",\"staffCode\":\"E1\",\"userSecurityVersion\":1,\"tenantPolicyVersion\":1,\"policies\":[{\"id\":\"" + POLICY + "\",\"scopeType\":\"" + type + "\",\"roleCode\":\"assigned-role\"}],\"externalBindings\":" + aliases + "}", MediaType.APPLICATION_JSON));
    }
    private void emptyCustomersAndCity(String employeeCode) {
        directory("crm.test", "/api/v1/crm/internal-customers", Map.of("ownerEmployeeCode", employeeCode, "statusCode", "ACTIVE"))
                .andRespond(withSuccess("{\"total\":0,\"items\":[]}", MediaType.APPLICATION_JSON));
        directory("crm.test", "/api/v1/crm/customer-areas", Map.of("q", "BJ"))
                .andRespond(withSuccess("{\"total\":1,\"items\":[{\"code\":\"BJ\",\"name\":\"北京\",\"status\":\"ACTIVE\"}]}", MediaType.APPLICATION_JSON));
    }
    private ResponseActions directory(String host, String path, Map<String, String> query) {
        return server.expect(request -> {
            assertThat(request.getURI().getHost()).isEqualTo(host);
            assertThat(request.getURI().getPath()).isEqualTo(path);
            var params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
            query.forEach((key, value) -> assertThat(params.getFirst(key)).isEqualTo(value));
        }).andExpect(header(RequestHeaders.TENANT_ID, TENANT.toString()))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE"))
                .andExpect(headerDoesNotExist(RequestHeaders.USER_ID))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(request -> assertThat(request.getHeaders().getFirst(RequestHeaders.CONTEXT_SIGNATURE)).isNotBlank());
    }
    private static TrustedContextSigner signer() {
        var properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
