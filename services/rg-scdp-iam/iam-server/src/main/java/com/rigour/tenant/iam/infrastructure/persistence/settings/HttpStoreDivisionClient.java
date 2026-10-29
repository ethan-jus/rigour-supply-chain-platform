package com.rigour.tenant.iam.infrastructure.persistence.settings;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.tenant.iam.application.port.out.StoreDivisionClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class HttpStoreDivisionClient implements StoreDivisionClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI uri;

    public HttpStoreDivisionClient(RestClient.Builder builder, TrustedContextSigner signer,
            @Value("${rigour.business-settings.base-url:http://rigour-business-settings-service}") String baseUrl) {
        this.signer = signer;
        this.uri = URI.create(baseUrl.replaceAll("/+$", "") + "/internal/v1/business-settings/admin-divisions");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public AdminDivisionCatalog published(UUID tenant) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        headers.put(RequestHeaders.PRINCIPAL_ID, UUID.nameUUIDFromBytes("iam-admin-division-reader".getBytes(StandardCharsets.UTF_8)).toString());
        headers.put(RequestHeaders.TENANT_ID, tenant.toString());
        headers.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        headers.put(RequestHeaders.SESSION_VERSION, "0");
        headers.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        headers.put(RequestHeaders.PERMISSIONS, "settings:admin-division:read");
        var signature = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
        try {
            ApiResponse<AdminDivisionCatalog> response = client.get().uri(uri)
                    .headers(h -> headers.forEach(h::set)).retrieve().body(new ParameterizedTypeReference<>() {});
            if (response != null && "OK".equals(response.code()) && response.data() != null) return response.data();
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "行政区划暂不可用，请确认公共服务已启动且区划版本已发布", exception);
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "行政区划目录响应异常");
    }
}
