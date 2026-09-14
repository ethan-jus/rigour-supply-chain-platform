package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.application.port.out.BiReconciliationUnitDictionary;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiErrorDetail;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/** 与现有 BI 目录客户端相同的可信服务签名；只 GET 历史单位映射，无同步或管理写权限。 */
@Component
public class HttpBiReconciliationUnitDictionary implements BiReconciliationUnitDictionary {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI uri;

    @Autowired
    public HttpBiReconciliationUnitDictionary(TrustedContextSigner signer,
            @Value("${rigour.business-settings.base-url:${RIGOUR_BUSINESS_SETTINGS_BASE_URL:http://localhost:26892}}") String baseUrl) {
        this(defaultBuilder(), signer, baseUrl);
    }

    HttpBiReconciliationUnitDictionary(RestClient.Builder builder, TrustedContextSigner signer, String baseUrl) {
        URI base = URI.create(baseUrl);
        if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) throw new IllegalArgumentException("Invalid Settings base URL");
        this.uri = UriComponentsBuilder.fromUriString(baseUrl.replaceAll("/+$", ""))
                .path("/api/v1/business-settings/dictionaries/resolve").queryParam("dictionaryCode", "PRODUCT_UNIT").build().toUri();
        this.client = builder.build();
        this.signer = signer;
    }

    private static RestClient.Builder defaultBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }

    @Override public Map<String, String> productUnits(CallerIdentity actor) {
        if (actor == null || actor.tenantId() == null) throw unavailable();
        try {
            var result = client.get().uri(uri).headers(headers -> signed(actor).forEach(headers::set))
                    .retrieve().body(JsonNode.class);
            if (result == null || !"OK".equals(result.path("code").asText())
                    || !"PRODUCT_UNIT".equals(result.path("data").path("dictionary").path("dictionaryCode").asText())) throw unavailable();
            JsonNode items = result.path("data").path("items");
            if (!items.isArray() || items.isEmpty() || items.size() > 10000) throw unavailable();
            Map<String, String> mapping = new LinkedHashMap<>();
            for (JsonNode item : items) {
                String code = text(item, "dictionaryItemCode"), name = text(item, "dictionaryItemName");
                if (!"PRODUCT_UNIT".equals(text(item, "dictionaryCode")) || code == null || name == null
                        || mapping.putIfAbsent(code, name) != null) throw unavailable();
            }
            return Map.copyOf(mapping);
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private Map<String, String> signed(CallerIdentity actor) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        headers.put(RequestHeaders.PRINCIPAL_ID, UUID.nameUUIDFromBytes("rigour-analytics-bi-reconciliation-dictionary".getBytes(StandardCharsets.UTF_8)).toString());
        headers.put(RequestHeaders.TENANT_ID, actor.tenantId().toString());
        headers.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        headers.put(RequestHeaders.SESSION_VERSION, "0");
        headers.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        headers.put(RequestHeaders.PERMISSIONS, "business-settings:dict:read");
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        String requestId = RequestContext.getRequestId();
        headers.put(RequestHeaders.REQUEST_ID, requestId == null ? UUID.randomUUID().toString() : requestId);
        return headers;
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().strip() : null;
    }
    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "商品单位字典暂不可用，无法可靠核对数量单位。请检查字典服务及只读调用配置后重试。",
                List.of(new ApiErrorDetail(null, "BI_PRODUCT_UNIT_DICTIONARY_UNAVAILABLE", "请恢复PRODUCT_UNIT字典只读API；无需扩大数据库授权或重新导入订单。")));
    }
}
