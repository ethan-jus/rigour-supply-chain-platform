package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.application.port.out.BiProductImages;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/** 按已授权商品 ID 批量获取 ERP 主图；图片不可用时不影响业绩数据。 */
@Component
public class HttpBiProductImages implements BiProductImages {
    private static final Logger log = LoggerFactory.getLogger(HttpBiProductImages.class);
    private static final String PRINCIPAL = UUID.nameUUIDFromBytes(
            "service:rigour-analytics-bi-product-images".getBytes(StandardCharsets.UTF_8)).toString();
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String baseUrl;

    @Autowired
    public HttpBiProductImages(TrustedContextSigner signer,
            @Value("${rigour.erp.base-url:${RIGOUR_ERP_BASE_URL:http://rigour-erp-core-service}}") String baseUrl,
            RestClient.Builder builder) {
        this(timed(builder), signer, baseUrl);
    }

    HttpBiProductImages(RestClient.Builder builder, TrustedContextSigner signer, String baseUrl) {
        this.client = builder.build();
        this.signer = signer;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    private static RestClient.Builder timed(RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return builder.requestFactory(factory);
    }

    @Override
    public Map<String, String> urls(String tenantId, List<String> productIds) {
        var ids = productIds.stream().filter(id -> id != null && id.matches("[1-9][0-9]*"))
                .distinct().toList();
        var urls = new LinkedHashMap<String, String>();
        try {
            for (int offset = 0; offset < ids.size(); offset += 200) {
                var batch = ids.subList(offset, Math.min(offset + 200, ids.size()));
                URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                        .path("/api/v1/erp/product-management/products")
                        .queryParam("begin", 0).queryParam("step", 200)
                        .queryParam("productIds", String.join(",", batch)).build().encode().toUri();
                var response = client.get().uri(uri)
                        .headers(h -> signed(tenantId, uri).forEach(h::set)).retrieve().body(JsonNode.class);
                if (response == null || !"OK".equals(response.path("code").asText())
                        || !response.path("data").path("items").isArray()) {
                    throw new IllegalStateException("Invalid ERP product response");
                }
                for (var item : response.path("data").path("items")) {
                    String id = item.path("id").asText();
                    var image = item.path("mainImageUrl");
                    if (batch.contains(id) && image.isTextual() && !image.asText().isBlank()) {
                        urls.put(id, image.asText());
                    }
                }
            }
        } catch (RuntimeException ex) {
            // 不记录响应正文或带签名的图片 URL，也不把图片故障转换为整个看板的失败。
            log.warn("BI商品主图读取失败 errorType={}", ex.getClass().getSimpleName());
        }
        return Map.copyOf(urls);
    }

    private Map<String, String> signed(String tenant, URI uri) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        headers.put(RequestHeaders.PRINCIPAL_ID, PRINCIPAL);
        headers.put(RequestHeaders.TENANT_ID, tenant);
        headers.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        headers.put(RequestHeaders.SESSION_VERSION, "0");
        headers.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        headers.put(RequestHeaders.PERMISSIONS, "erp:product:read");
        var signature = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
        return headers;
    }
}
