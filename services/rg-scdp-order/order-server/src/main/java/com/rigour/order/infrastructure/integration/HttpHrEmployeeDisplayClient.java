package com.rigour.order.infrastructure.integration;

import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Order到HR员工主档的HTTP客户端；按员工编码补齐页面展示名。 */
public final class HttpHrEmployeeDisplayClient implements HrEmployeeDisplayClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpHrEmployeeDisplayClient(RestClient.Builder builder,
                                       TrustedContextSigner signer,
                                       String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(baseUrl);
    }

    @Override
    public List<EmployeeDisplay> resolve(CallerIdentity caller, Set<String> employeeCodes) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("HR员工展示查询必须携带租户上下文");
        }
        if (employeeCodes == null || employeeCodes.isEmpty()) return List.of();
        List<EmployeeDisplay> result = new ArrayList<>();
        for (String employeeCode : employeeCodes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(200)
                .toList()) {
            EmployeeDisplay display = resolveOne(caller, employeeCode);
            if (display != null) result.add(display);
        }
        return List.copyOf(result);
    }

    private EmployeeDisplay resolveOne(CallerIdentity caller, String employeeCode) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/api/v1/hr/employees")
                .queryParam("begin", 0)
                .queryParam("step", 1)
                .queryParam("employeeCode", employeeCode)
                .build()
                .encode()
                .toUri();
        ApiResponse<EmployeePage> response = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("GET", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("HR员工展示查询返回空响应");
        }
        List<Map<String, Object>> rows = response.data().items();
        if (rows == null || rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        String code = text(row.get("employeeCode"));
        String name = text(row.get("employeeName"));
        if (code == null || name == null) return null;
        return new EmployeeDisplay(code, name, text(row.get("employmentStatus")));
    }

    private Map<String, String> signedHeaders(String method, URI uri, CallerIdentity caller) {
        Map<String, String> headers = new LinkedHashMap<>();
        put(headers, RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        put(headers, RequestHeaders.PRINCIPAL_ID, caller.principalId());
        put(headers, RequestHeaders.TENANT_ID, caller.tenantId());
        put(headers, RequestHeaders.USER_ID, caller.userId());
        put(headers, RequestHeaders.PLATFORM_USER_ID, caller.platformUserId());
        put(headers, RequestHeaders.SESSION_ID, caller.sessionId());
        put(headers, RequestHeaders.SESSION_VERSION, caller.sessionVersion());
        put(headers, RequestHeaders.USER_SECURITY_VERSION, caller.userSecurityVersion());
        put(headers, RequestHeaders.TENANT_POLICY_VERSION, caller.tenantPolicyVersion());
        put(headers, RequestHeaders.ROLES, joined(caller.roles()));
        put(headers, RequestHeaders.PERMISSIONS, joined(caller.permissions()));
        TrustedContextSigner.SignedContext signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("HR服务地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("HR服务地址必须使用http或https");
        }
        return uri;
    }

    private static void put(Map<String, String> target, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(name, String.valueOf(value));
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }

    private static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private record EmployeePage(long total, int begin, int step, List<Map<String, Object>> items) {
    }
}
