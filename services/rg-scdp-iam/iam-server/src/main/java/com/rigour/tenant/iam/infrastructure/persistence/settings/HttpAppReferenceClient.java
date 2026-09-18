package com.rigour.tenant.iam.infrastructure.persistence.settings;

import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.tenant.iam.application.port.out.AppReferenceClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Component
public final class HttpAppReferenceClient implements AppReferenceClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final Map<String, URI> endpoints;

    public HttpAppReferenceClient(
            TrustedContextSigner signer,
            @Value(
                            "${rigour.iam.hr-base-url:${HR_PAYROLL_BASE_URL:http://rigour-hr-payroll-service:26889}}")
                    String hr,
            @Value(
                            "${rigour.iam.crm-base-url:${MERCHANT_CRM_BASE_URL:http://rigour-merchant-crm-service:26883}}")
                    String crm,
            @Value(
                            "${rigour.iam.erp-base-url:${ERP_CORE_BASE_URL:http://rigour-erp-core-service:26884}}")
                    String erp) {
        this.signer = signer;
        endpoints =
                Map.of(
                        "DEPARTMENT",
                        endpoint(hr, "hr"),
                        "REGION",
                        endpoint(crm, "crm"),
                        "WAREHOUSE",
                        endpoint(erp, "erp"));
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().requestFactory(factory).build();
    }

    private static URI endpoint(String base, String path) {
        URI uri =
                URI.create(
                        base.replaceAll("/+$", "")
                                + "/api/v1/"
                                + path
                                + "/authorization/references");
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null)
            throw new IllegalArgumentException("主数据服务地址无效");
        return uri;
    }

    @Override
    public List<Reference> references(UUID tenant, String dimension) {
        URI uri = endpoints.get(dimension);
        if (uri == null) throw new IllegalArgumentException("未知范围维度");
        Map<String, String> h = new LinkedHashMap<>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        h.put(
                RequestHeaders.PRINCIPAL_ID,
                UUID.nameUUIDFromBytes(
                                "rigour-iam-scope-reader"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString());
        h.put(RequestHeaders.TENANT_ID, tenant.toString());
        h.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        h.put(RequestHeaders.SESSION_VERSION, "0");
        h.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        h.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        h.put(RequestHeaders.PERMISSIONS, "supply:scope:reference-read");
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        try {
            ApiResponse<List<Reference>> result =
                    client.get()
                            .uri(uri)
                            .headers(headers -> h.forEach(headers::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (result == null || !"OK".equals(result.code()) || result.data() == null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "授权主数据目录不可用");
            return result.data();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "授权主数据目录暂时无法核验", e);
        }
    }
}
