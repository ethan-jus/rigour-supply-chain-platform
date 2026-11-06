package com.rigour.erp.infrastructure.integration;

import com.rigour.erp.application.port.out.WarehouseLocationCatalog;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public final class HttpWarehouseLocationCatalog implements WarehouseLocationCatalog {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String base;

    public HttpWarehouseLocationCatalog(RestClient.Builder builder, TrustedContextSigner signer,
            @Value("${rigour.business-settings.base-url:http://rigour-business-settings-service}") String base) {
        this.signer = signer;
        this.base = base.replaceAll("/+$", "");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public AdminDivisionCatalog published(String version) {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("warehouse-location");
        var uriBuilder = UriComponentsBuilder.fromUriString(base)
                .path("/internal/v1/business-settings/admin-divisions");
        if (version != null) uriBuilder.queryParam("version", version);
        URI uri = uriBuilder.build().encode().toUri();
        var h = new LinkedHashMap<String, String>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        h.put(RequestHeaders.PRINCIPAL_ID, UUID.nameUUIDFromBytes(
                "erp-warehouse-location-reader".getBytes(StandardCharsets.UTF_8)).toString());
        h.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        h.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        h.put(RequestHeaders.SESSION_VERSION, "0");
        h.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        h.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        h.put(RequestHeaders.PERMISSIONS, "settings:admin-division:read");
        var signature = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
        try {
            ApiResponse<AdminDivisionCatalog> result = client.get().uri(uri)
                    .headers(headers -> h.forEach(headers::set)).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (result != null && "OK".equals(result.code()) && result.data() != null)
                return result.data();
        } catch (RestClientException e) {
            throw unavailable();
        }
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "行政区划目录暂不可用，请确认目录已发布后重试", List.of());
    }
}
