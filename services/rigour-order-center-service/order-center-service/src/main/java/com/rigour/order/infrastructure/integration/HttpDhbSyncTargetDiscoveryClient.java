package com.rigour.order.infrastructure.integration;

import com.rigour.integration.api.v1.DhbIntegrationInternalApi;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.order.application.port.out.DhbSyncTargetDiscoveryClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.TrustedContextSigner;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 通过非Gateway内部路径发现Integration中的跨租户订单同步目标。 */
public final class HttpDhbSyncTargetDiscoveryClient implements DhbSyncTargetDiscoveryClient {

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI integrationBaseUri;

    public HttpDhbSyncTargetDiscoveryClient(RestClient.Builder builder,
                                            TrustedContextSigner signer,
                                            String integrationBaseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.integrationBaseUri = baseUri(integrationBaseUrl);
    }

    @Override
    public List<SyncTargetView> discover(CallerIdentity serviceCaller) {
        Objects.requireNonNull(serviceCaller, "serviceCaller不能为空");
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbIntegrationInternalApi.SYNC_TARGETS_PATH)
                .build()
                .encode()
                .toUri();
        Map<String, String> context = contextHeaders(serviceCaller);
        TrustedContextSigner.SignedContext signed = signer.sign(
                "GET", uri.getRawPath(), uri.getRawQuery(), context);
        context.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        context.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        context.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .headers(headers -> context.forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
    }

    private static Map<String, String> contextHeaders(CallerIdentity caller) {
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
        return headers;
    }

    private static void put(Map<String, String> target, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(name, String.valueOf(value));
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Integration地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Integration地址必须使用http或https");
        }
        return uri;
    }

    private static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
