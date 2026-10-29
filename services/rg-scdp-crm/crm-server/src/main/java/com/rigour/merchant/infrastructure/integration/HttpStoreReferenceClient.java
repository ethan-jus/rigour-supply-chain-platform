package com.rigour.merchant.infrastructure.integration;

import com.rigour.merchant.application.port.out.StoreReferenceClient;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.exception.StateConflictException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class HttpStoreReferenceClient implements StoreReferenceClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String iam;
    private final String foundation;
    private final com.rigour.settings.client.EffectiveDictionaryClient dictionaries;
    public HttpStoreReferenceClient(RestClient.Builder builder, TrustedContextSigner signer,
            @Value("${rigour.iam.base-url:http://rigour-tenant-iam-service}") String iam,
            @Value("${rigour.business-settings.base-url:http://rigour-business-settings-service}") String foundation) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = builder.requestFactory(factory).build(); this.signer = signer;
        this.dictionaries = new com.rigour.settings.client.EffectiveDictionaryClient(builder,signer,foundation,"rigour-merchant-crm-service");
        this.iam = iam.replaceAll("/+$", ""); this.foundation = foundation.replaceAll("/+$", "");
    }
    public Set<String> storeTypeCodes(String tenant){return dictionaries.activeCodes(tenant,"CRM_STORE_TYPE");}
    public StoreAccessView access(String action) {
        var caller = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(caller.principalScope()) || caller.tenantId() == null || caller.userId() == null)
            throw new AuthorizationDeniedException("store-user");
        URI uri = UriComponentsBuilder.fromUriString(iam).path("/internal/v1/iam/supply/store-access").queryParam("action", action).build().encode().toUri();
        var h = baseHeaders(caller);
        h.put(RequestHeaders.PRINCIPAL_SCOPE, "TENANT");
        h.put(RequestHeaders.PRINCIPAL_ID, caller.principalId().toString());
        h.put(RequestHeaders.USER_ID, caller.userId().toString());
        var result = client.get().uri(uri).headers(headers -> signed(uri, h).forEach(headers::set)).retrieve().body(StoreAccessView.class);
        if (result == null || !caller.tenantId().toString().equals(result.tenantId())
                || !caller.userId().toString().equals(result.userId()) || !action.equals(result.action()))
            throw new IllegalStateException("门店授权上下文不一致");
        return result;
    }
    public AdminDivisionCatalog divisions() {
        var caller = AuthorizationContext.requireCurrent();
        URI uri = URI.create(foundation + "/internal/v1/business-settings/admin-divisions");
        Map<String,String> h = new LinkedHashMap<>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE,"SERVICE");
        h.put(RequestHeaders.PRINCIPAL_ID,UUID.nameUUIDFromBytes("crm-admin-division-reader".getBytes(StandardCharsets.UTF_8)).toString());
        h.put(RequestHeaders.TENANT_ID,caller.tenantId().toString());
        h.put(RequestHeaders.SESSION_ID,UUID.randomUUID().toString());
        h.put(RequestHeaders.SESSION_VERSION,"0"); h.put(RequestHeaders.USER_SECURITY_VERSION,"0"); h.put(RequestHeaders.TENANT_POLICY_VERSION,"0");
        h.put(RequestHeaders.PERMISSIONS,"settings:admin-division:read");
        try {
            ApiResponse<AdminDivisionCatalog> result = client.get().uri(uri).headers(headers -> signed(uri,h).forEach(headers::set)).retrieve().body(new ParameterizedTypeReference<>() {});
            if (result != null && "OK".equals(result.code()) && result.data() != null) return result.data();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409)
                throw new StateConflictException("行政区划尚未发布，请先导入并核验行政区划数据");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "行政区划服务暂不可用，请稍后重试", List.of());
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "行政区划服务暂不可用，请稍后重试", List.of());
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "行政区划目录响应异常，请稍后重试", List.of());
    }
    private Map<String,String> baseHeaders(CallerIdentity c) {
        Map<String,String> h = new LinkedHashMap<>();
        h.put(RequestHeaders.TENANT_ID,c.tenantId().toString()); h.put(RequestHeaders.SESSION_ID,c.sessionId().toString());
        h.put(RequestHeaders.SESSION_VERSION,Long.toString(c.sessionVersion()));
        h.put(RequestHeaders.USER_SECURITY_VERSION,Long.toString(c.userSecurityVersion()));
        h.put(RequestHeaders.TENANT_POLICY_VERSION,Long.toString(c.tenantPolicyVersion())); return h;
    }
    private Map<String,String> signed(URI uri, Map<String,String> headers) {
        var signature=signer.sign("GET",uri.getRawPath(),uri.getRawQuery(),headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID,signature.keyId()); headers.put(RequestHeaders.CONTEXT_TIMESTAMP,signature.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE,signature.signature()); return headers;
    }
}
