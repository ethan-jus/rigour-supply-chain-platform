package com.rigour.order.infrastructure.integration;

import com.rigour.order.application.port.out.IamStaffDisplayClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
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

/** Order到IAM人员中心的HTTP客户端；按员工编码批量补齐页面展示名。 */
public final class HttpIamStaffDisplayClient implements IamStaffDisplayClient {
    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpIamStaffDisplayClient(RestClient.Builder builder,
                                     TrustedContextSigner signer,
                                     String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(baseUrl);
    }

    @Override
    public List<StaffDisplay> resolve(CallerIdentity caller, Set<String> staffCodes) {
        if (caller == null || caller.tenantId() == null) {
            throw new IllegalArgumentException("IAM人员展示查询必须携带租户上下文");
        }
        if (staffCodes == null || staffCodes.isEmpty()) return List.of();
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/iam/staff/display")
                .build()
                .encode()
                .toUri();
        ApiResponse<List<StaffDisplay>> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(new StaffDisplayRequest(staffCodes.stream().toList()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException("IAM人员展示查询返回空响应");
        }
        return response.data();
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
        if (value == null || value.isBlank()) throw new IllegalArgumentException("IAM服务地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("IAM服务地址必须使用http或https");
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

    private record StaffDisplayRequest(List<String> staffCodes) {
        StaffDisplayRequest {
            staffCodes = staffCodes == null ? List.of() : List.copyOf(staffCodes);
        }
    }
}
