package com.rigour.tenant.iam.client;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** 仅封装签名和 HTTP 契约；缓存不能跨请求使用。 */
public final class HttpSupplyAuthorizationClient implements SupplyAuthorizationClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI base;

    public HttpSupplyAuthorizationClient(TrustedContextSigner signer, String baseUrl) {
        this.signer = signer;
        this.base = URI.create(baseUrl.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getUserInfo() != null)
            throw new IllegalArgumentException("IAM 地址无效");
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public SupplyAuthorizationView authorization(CallerIdentity caller, String action) {
        return read(caller, action, "authorization");
    }

    @Override
    public SupplyAuthorizationView candidate(CallerIdentity caller, String action) {
        return read(caller, action, "candidate");
    }

    private SupplyAuthorizationView read(CallerIdentity caller, String action, String endpoint) {
        if (caller == null || !"TENANT".equals(caller.principalScope()))
            throw new AuthorizationDeniedException("supply-original-user");
        URI uri =
                UriComponentsBuilder.fromUri(base)
                        .path("/internal/v1/iam/supply/" + endpoint)
                        .queryParam("action", action)
                        .build()
                        .encode()
                        .toUri();
        var h = headers(caller, "GET", uri);
        SupplyAuthorizationView result =
                client.get()
                        .uri(uri)
                        .headers(headers -> h.forEach(headers::set))
                        .retrieve()
                        .body(SupplyAuthorizationView.class);
        if (result == null
                || !caller.tenantId().equals(result.tenantId())
                || !caller.userId().equals(result.userId())
                || !action.equals(result.action())
                || !Set.of("PREPARING", "ACTIVE").contains(result.mode()))
            throw new IllegalStateException("IAM 授权上下文不一致");
        return result;
    }

    @Override
    public void observe(CallerIdentity caller, String action, String legacyAction) {
        URI uri = base.resolve("/internal/v1/iam/supply/observations");
        client.post()
                .uri(uri)
                .headers(h -> headers(caller, "POST", uri).forEach(h::set))
                .body(
                        new com.rigour.tenant.iam.api.v1.IamSupplyAuthorizationApi.Observation(
                                action, legacyAction))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void observeData(
            CallerIdentity caller,
            com.rigour.tenant.iam.api.v1.model.SupplyDataObservation request) {
        URI uri = base.resolve("/internal/v1/iam/supply/data-observations");
        client.post()
                .uri(uri)
                .headers(h -> headers(caller, "POST", uri).forEach(h::set))
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, String> headers(CallerIdentity caller, String method, URI uri) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        h.put(RequestHeaders.PRINCIPAL_ID, caller.principalId().toString());
        h.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        h.put(RequestHeaders.USER_ID, caller.userId().toString());
        h.put(RequestHeaders.SESSION_ID, caller.sessionId().toString());
        h.put(RequestHeaders.SESSION_VERSION, Long.toString(caller.sessionVersion()));
        h.put(RequestHeaders.USER_SECURITY_VERSION, Long.toString(caller.userSecurityVersion()));
        h.put(RequestHeaders.TENANT_POLICY_VERSION, Long.toString(caller.tenantPolicyVersion()));
        var signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return h;
    }
}
