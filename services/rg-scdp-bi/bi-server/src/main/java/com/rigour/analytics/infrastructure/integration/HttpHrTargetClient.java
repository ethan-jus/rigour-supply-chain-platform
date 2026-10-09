package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.application.port.out.HrTargetClient;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.*;

@Component
public final class HttpHrTargetClient implements HrTargetClient {
    private final TrustedContextSigner signer;
    private final RestClient client;
    private final String base;

    public HttpHrTargetClient(
            TrustedContextSigner signer,
            RestClient.Builder builder,
            @Value("${rigour.analytics.scope.hr-base-url:http://rigour-hr-payroll-service}")
                    String base) {
        this.signer = signer;
        this.base = base.replaceAll("/+$", "");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.requestFactory(factory).build();
    }

    public List<Target> values(String tenant, String from, String to) {
        java.time.YearMonth.parse(from);
        java.time.YearMonth.parse(to);
        var uri = URI.create(base + "/api/v1/hr/target-settings/values?from=" + from + "&to=" + to);
        var caller =
                new CallerIdentity(
                        "SERVICE",
                        UUID.nameUUIDFromBytes(
                                "rigour-bi-target-reader"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        UUID.fromString(tenant),
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("hr:targets:service-read"));
        try {
            var result =
                    client.get()
                            .uri(uri)
                            .headers(h -> signed(caller, uri, "GET").forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<ApiResponse<List<Target>>>() {});
            if (result == null || !"OK".equals(result.code()) || result.data() == null)
                throw new IllegalStateException("HR target response unavailable");
            return result.data();
        } catch (org.springframework.web.client.RestClientException | IllegalStateException e) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "人事指标暂时无法读取，请稍后重试", List.of());
        }
    }

    private Map<String, String> signed(CallerIdentity caller, URI uri, String method) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        headers.put(RequestHeaders.PRINCIPAL_ID, caller.principalId().toString());
        headers.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        if (caller.userId() != null)
            headers.put(RequestHeaders.USER_ID, caller.userId().toString());
        headers.put(RequestHeaders.SESSION_ID, caller.sessionId().toString());
        headers.put(RequestHeaders.SESSION_VERSION, Long.toString(caller.sessionVersion()));
        headers.put(
                RequestHeaders.USER_SECURITY_VERSION, Long.toString(caller.userSecurityVersion()));
        headers.put(
                RequestHeaders.TENANT_POLICY_VERSION, Long.toString(caller.tenantPolicyVersion()));
        headers.put(RequestHeaders.ROLES, String.join(",", caller.roles()));
        headers.put(RequestHeaders.PERMISSIONS, String.join(",", caller.permissions()));
        var signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }
}
