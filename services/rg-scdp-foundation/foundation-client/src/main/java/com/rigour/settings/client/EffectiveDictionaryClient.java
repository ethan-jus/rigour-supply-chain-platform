package com.rigour.settings.client;

import com.rigour.settings.api.v1.BusinessDictionaryApi;
import com.rigour.settings.api.v1.model.EffectiveDictView;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 仅 GET 有效字典，不调用带自动补齐副作用的字典同步客户端。 */
public final class EffectiveDictionaryClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String baseUrl;
    private final String serviceName;

    public EffectiveDictionaryClient(
            RestClient.Builder builder,
            TrustedContextSigner signer,
            String baseUrl,
            String serviceName) {
        this.client = builder.build();
        this.signer = signer;
        this.baseUrl = baseUrl;
        if (serviceName == null || !serviceName.matches("rigour-[a-z-]+-service"))
            throw new IllegalArgumentException("服务名称无效");
        this.serviceName = serviceName;
    }

    public Set<String> activeCodes(String tenantId, String dictionaryCode) {
        UUID.fromString(tenantId);
        if (dictionaryCode == null || !dictionaryCode.matches("[A-Z][A-Z0-9_]{0,49}"))
            throw new IllegalArgumentException("字典编码无效");
        var uri =
                UriComponentsBuilder.fromUriString(baseUrl)
                        .path(BusinessDictionaryApi.BASE_PATH + "/effective")
                        .queryParam("dictionaryCode", dictionaryCode)
                        .build()
                        .encode()
                        .toUri();
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        headers.put(
                RequestHeaders.PRINCIPAL_ID,
                UUID.nameUUIDFromBytes(("service:" + serviceName).getBytes(StandardCharsets.UTF_8))
                        .toString());
        headers.put(RequestHeaders.TENANT_ID, tenantId);
        headers.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        headers.put(RequestHeaders.SESSION_VERSION, "0");
        headers.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        headers.put(RequestHeaders.PERMISSIONS, "business-settings:dict:read");
        var signature = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
        try {
            ApiResponse<EffectiveDictView> response =
                    client.get()
                            .uri(uri)
                            .headers(h -> headers.forEach(h::set))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (response == null
                    || !"OK".equals(response.code())
                    || response.data() == null
                    || response.data().dictionary() == null
                    || !dictionaryCode.equals(response.data().dictionary().dictionaryCode())) {
                throw new IllegalStateException("Invalid dictionary");
            }
            return com.rigour.settings.client.DictionaryItems.activeCodes(response.data());
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "业务字典暂不可核验，请稍后重试", List.of());
        }
    }
}
