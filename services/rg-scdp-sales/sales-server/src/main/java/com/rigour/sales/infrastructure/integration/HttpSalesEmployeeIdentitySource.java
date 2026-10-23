package com.rigour.sales.infrastructure.integration;

import com.rigour.hr.api.v1.model.HrEmployeeIdentityView;
import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/** 只作用于正式销售档案；不为临时打卡添加 IAM 全局过滤器。 */
@Component
public final class HttpSalesEmployeeIdentitySource implements SalesEmployeeIdentitySource {
    private static final String ACTION = "sales:context:read";
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI iamBase;
    private final URI hrBase;

    public HttpSalesEmployeeIdentitySource(TrustedContextSigner signer, RestClient.Builder builder,
            @Value("${rigour.sales.identity.iam-base-url:${IAM_BASE_URL:http://rigour-tenant-iam-service}}") String iam,
            @Value("${rigour.sales.identity.hr-base-url:${HR_PAYROLL_BASE_URL:http://rigour-hr-payroll-service}}") String hr) {
        this.signer = signer;
        this.iamBase = URI.create(iam.replaceAll("/+$", ""));
        this.hrBase = URI.create(hr.replaceAll("/+$", ""));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = builder.requestFactory(factory).build();
    }

    @Override
    public Employee current(CallerIdentity caller) {
        URI iamUri = UriComponentsBuilder.fromUri(iamBase).path("/internal/v1/iam/supply/authorization")
                .queryParam("action", ACTION).build().toUri();
        SupplyAuthorizationView authorization;
        try {
            authorization = client.get().uri(iamUri)
                    .headers(h -> signed(iamUri, callerHeaders(caller)).forEach(h::set))
                    .retrieve().body(SupplyAuthorizationView.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                throw new BusinessException(ErrorCode.FORBIDDEN);
            throw unavailable();
        } catch (RestClientException e) { throw unavailable(); }
        if (authorization == null || !"ACTIVE".equals(authorization.mode())
                || !caller.tenantId().equals(authorization.tenantId())
                || !caller.userId().equals(authorization.userId()) || !ACTION.equals(authorization.action()))
            throw unavailable();
        if (!authorization.functionAllowed() || !authorization.permissions().contains(ACTION)
                // 旧会话中任何销售权限被撤销时保守拒绝，不能用仍有效的 context 权限执行旧写权限。
                || caller.permissions().stream().filter(p -> p.startsWith("sales:"))
                    .anyMatch(p -> !authorization.permissions().contains(p)))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        String code = authorization.employeeCode();
        if (code == null || code.isBlank()) throw new BusinessException(ErrorCode.SALES_IDENTITY_UNBOUND);

        URI hrUri = UriComponentsBuilder.fromUri(hrBase).path("/api/v1/hr/employee-identities/{code}")
                .buildAndExpand(code).encode().toUri();
        ApiResponse<HrEmployeeIdentityView> response;
        try {
            response = client.get().uri(hrUri)
                    .headers(h -> signed(hrUri, hrHeaders(caller)).forEach(h::set))
                    .retrieve().body(new ParameterizedTypeReference<>() {});
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) throw new BusinessException(ErrorCode.SALES_IDENTITY_UNBOUND);
            throw unavailable();
        } catch (RestClientException e) { throw unavailable(); }
        if (response == null || !"OK".equals(response.code()) || response.data() == null
                || !code.equals(response.data().employeeCode()) || response.data().id() == null
                || response.data().id() <= 0) throw unavailable();
        var employee = response.data();
        if (!employee.usable()) throw new BusinessException(ErrorCode.SALES_PROFILE_INACTIVE);
        return new Employee(employee.id(), code, employee.employeeName(), employee.employeeRevision(),
                employee.organizationVersion(), employee.accessVersion());
    }

    private Map<String, String> callerHeaders(CallerIdentity caller) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        h.put(RequestHeaders.PRINCIPAL_ID, caller.principalId().toString());
        h.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        h.put(RequestHeaders.USER_ID, caller.userId().toString());
        h.put(RequestHeaders.SESSION_ID, caller.sessionId().toString());
        h.put(RequestHeaders.SESSION_VERSION, Long.toString(caller.sessionVersion()));
        h.put(RequestHeaders.USER_SECURITY_VERSION, Long.toString(caller.userSecurityVersion()));
        h.put(RequestHeaders.TENANT_POLICY_VERSION, Long.toString(caller.tenantPolicyVersion()));
        return h;
    }

    private Map<String, String> hrHeaders(CallerIdentity caller) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        h.put(RequestHeaders.PRINCIPAL_ID, UUID.nameUUIDFromBytes(
                "rigour-sales-employee-reader".getBytes(StandardCharsets.UTF_8)).toString());
        h.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        h.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        h.put(RequestHeaders.SESSION_VERSION, "0");
        h.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        h.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        h.put(RequestHeaders.PERMISSIONS, "hr:employee:identity-read");
        return h;
    }

    private Map<String, String> signed(URI uri, Map<String, String> headers) {
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "员工身份暂时无法核验，请稍后重试");
    }
}
