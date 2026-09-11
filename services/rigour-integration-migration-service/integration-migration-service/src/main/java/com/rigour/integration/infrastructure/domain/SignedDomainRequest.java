package com.rigour.integration.infrastructure.domain;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Integration 到领域服务内部接口的可信上下文签名工具。 */
final class SignedDomainRequest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BODY_PREVIEW_LIMIT = 1_000;

    private SignedDomainRequest() {
    }

    static URI baseUri(String value, String serviceName) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(serviceName + "地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(serviceName + "地址必须使用http或https");
        }
        return uri;
    }

    static Map<String, String> signedHeaders(TrustedContextSigner signer, String method,
                                             URI uri, CallerIdentity caller) {
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
        TrustedContextSigner.SignedContext signed = signer.sign(method, uri.getRawPath(),
                uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    static <T> T required(ApiResponse<T> response, String serviceName) {
        if (response == null || !"OK".equals(response.code()) || response.data() == null) {
            throw new IllegalStateException(serviceName + "同步返回异常响应 code="
                    + (response == null ? null : response.code())
                    + " message=" + (response == null ? null : response.message()));
        }
        return response.data();
    }

    static <T> ApiResponse<T> readResponse(
            ClientHttpResponse response, TypeReference<ApiResponse<T>> responseType,
            String operation) throws IOException {
        byte[] body = response.getBody().readAllBytes();
        if (response.getStatusCode().isError()) {
            throw new RestClientResponseException(operation + " failed status="
                    + response.getStatusCode().value() + " body=" + bodyPreview(body),
                    response.getStatusCode(), response.getStatusText(),
                    response.getHeaders(), body, StandardCharsets.UTF_8);
        }
        try {
            return JSON.readValue(body, responseType);
        } catch (Exception error) {
            throw new RestClientException(operation + " response is not valid JSON status="
                    + response.getStatusCode().value()
                    + " contentType=" + response.getHeaders().getContentType()
                    + " body=" + bodyPreview(body), error);
        }
    }

    static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static String bodyPreview(byte[] body) {
        if (body == null || body.length == 0) return "";
        String value = new String(body, StandardCharsets.UTF_8)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
        if (value.length() <= BODY_PREVIEW_LIMIT) return value;
        return value.substring(0, BODY_PREVIEW_LIMIT) + "...";
    }

    private static void put(Map<String, String> target, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(name, String.valueOf(value));
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }
}
