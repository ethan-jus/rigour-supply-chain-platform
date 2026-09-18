package com.rigour.order.infrastructure.integration;

import com.rigour.order.application.port.out.CrmCustomerAreaDisplayClient;
import com.rigour.shared.context.CallerIdentity;
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

/** Order 到 CRM 客户归属地区主档的 HTTP 客户端；按地区编码补齐页面展示名。 */
public final class HttpCrmCustomerAreaDisplayClient implements CrmCustomerAreaDisplayClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpCrmCustomerAreaDisplayClient(RestClient.Builder builder,
                                            TrustedContextSigner signer,
                                            String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(baseUrl);
    }

    @Override
    public List<CustomerAreaDisplay> resolve(CallerIdentity caller, Set<String> areaCodes) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("CRM客户归属地区展示查询必须携带租户上下文");
        }
        if (areaCodes == null || areaCodes.isEmpty()) return List.of();
        List<CustomerAreaDisplay> result = new ArrayList<>();
        for (String areaCode : areaCodes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(500)
                .toList()) {
            CustomerAreaDisplay display = resolveOne(caller, areaCode);
            if (display != null) result.add(display);
        }
        return List.copyOf(result);
    }

    private CustomerAreaDisplay resolveOne(CallerIdentity caller, String areaCode) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/api/v1/crm/customer-areas")
                .queryParam("begin", 0)
                .queryParam("step", 10)
                .queryParam("q", areaCode)
                .build()
                .encode()
                .toUri();
        ApiResponse<CustomerAreaPage> response = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("GET", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("CRM客户归属地区展示查询返回空响应");
        }
        List<Map<String, Object>> rows = response.data().items();
        if (rows == null || rows.isEmpty()) return null;
        for (Map<String, Object> row : rows) {
            String code = text(row.get("code"));
            String name = text(row.get("name"));
            if (areaCode.equals(code) && name != null) return new CustomerAreaDisplay(code, name);
        }
        return null;
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
        if (value == null || value.isBlank()) throw new IllegalArgumentException("CRM服务地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("CRM服务地址必须使用http或https");
        }
        return uri;
    }

    private static void put(Map<String, String> headers, String name, Object value) {
        if (value == null) return;
        String text = value.toString();
        if (!text.isBlank()) headers.put(name, text);
    }

    private static String joined(Set<String> values) {
        if (values == null || values.isEmpty()) return "";
        return String.join(",", new TreeSet<>(values));
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private record CustomerAreaPage(List<Map<String, Object>> items) {
    }
}
