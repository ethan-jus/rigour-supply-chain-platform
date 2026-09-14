package com.rigour.order.infrastructure.integration;

import com.rigour.order.application.port.out.OrderRepairUnitDictionary;
import com.rigour.settings.api.v1.BusinessDictionaryApi;
import com.rigour.settings.api.v1.model.EffectiveDictView;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 仅 GET 有效字典，不调用带自动补齐副作用的字典同步客户端。 */
public final class HttpOrderRepairUnitDictionary implements OrderRepairUnitDictionary {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String baseUrl;
    public HttpOrderRepairUnitDictionary(RestClient.Builder builder, TrustedContextSigner signer, String baseUrl) {
        this.client = builder.build(); this.signer = signer; this.baseUrl = baseUrl;
    }
    @Override public Set<String> validUnits(String tenantId) {
        UUID.fromString(tenantId);
        var uri = UriComponentsBuilder.fromUriString(baseUrl).path(BusinessDictionaryApi.BASE_PATH + "/effective")
                .queryParam("dictionaryCode", "PRODUCT_UNIT").build().encode().toUri();
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        headers.put(RequestHeaders.PRINCIPAL_ID, UUID.nameUUIDFromBytes(
                "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8)).toString());
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
            ApiResponse<EffectiveDictView> response = client.get().uri(uri).headers(h -> headers.forEach(h::set))
                    .retrieve().body(new ParameterizedTypeReference<>() { });
            if (response == null || !"OK".equals(response.code()) || response.data() == null
                    || response.data().dictionary() == null || !"PRODUCT_UNIT".equals(response.data().dictionary().dictionaryCode())) {
                throw new IllegalStateException("Invalid dictionary");
            }
            return response.data().items().stream().filter(item -> "PRODUCT_UNIT".equals(item.dictionaryCode()))
                    .map(item -> item.dictionaryItemCode()).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "商品单位字典暂不可核验，请稍后重新预览", List.of());
        }
    }
}
