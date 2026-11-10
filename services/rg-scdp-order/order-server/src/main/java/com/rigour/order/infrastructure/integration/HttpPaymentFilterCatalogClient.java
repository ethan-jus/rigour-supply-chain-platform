package com.rigour.order.infrastructure.integration;

import com.rigour.order.api.v1.PaymentFilterApi.*;
import com.rigour.order.application.port.out.PaymentFilterCatalogClient;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 回款筛选只读商品目录；价格、成本及商品维护字段不出此边界。 */
@Component
public class HttpPaymentFilterCatalogClient implements PaymentFilterCatalogClient {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String base;
    public HttpPaymentFilterCatalogClient(RestClient.Builder builder, TrustedContextSigner signer,
            SimpleClientHttpRequestFactory factory,
            @Value("${rigour.erp.base-url:http://rigour-erp-core-service}") String base) {
        this.client = builder.requestFactory(factory).build(); this.signer = signer; this.base = base;
    }
    @Override public Catalog catalog(CallerIdentity caller) {
        var categories = rows(caller, "/api/v1/erp/product-categories", false).stream()
                .map(r -> new Category(text(r, "id"), text(r, "parentId"), text(r, "categoryName"))).toList();
        var products = rows(caller, "/api/v1/erp/product-management/products", true).stream()
                .map(r -> new Product(text(r, "id"), text(r, "productCode"), text(r, "productName"),
                        text(r, "categoryId"), variants(r.get("variants")))).toList();
        return new Catalog(categories, products);
    }
    private List<Map<String, Object>> rows(CallerIdentity caller, String path, boolean variants) {
        var result = new ArrayList<Map<String, Object>>();
        for (int begin = 0; begin < 10000; begin += 200) {
            var uri = UriComponentsBuilder.fromUriString(base).path(path)
                    .queryParam("begin", begin).queryParam("step", 200);
            if (variants) uri.queryParam("withVariants", true);
            URI target = uri.build().toUri();
            var response = client.get().uri(target).headers(h -> headers(caller, target).forEach(h::set))
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<Page>>() {});
            if (response == null || !"OK".equals(response.code()) || response.data() == null
                    || response.data().items() == null) throw new IllegalStateException("回款商品选项加载失败");
            var page = response.data();
            if (page.items().isEmpty() && begin < page.total()) throw new IllegalStateException("回款商品选项分页不完整");
            result.addAll(page.items());
            if (begin + page.items().size() >= page.total()) return List.copyOf(result);
        }
        throw new IllegalStateException("回款商品选项超出查询上限");
    }
    private Map<String, String> headers(CallerIdentity caller, URI uri) {
        var h = new LinkedHashMap<String, String>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        h.put(RequestHeaders.PRINCIPAL_ID, caller.principalId().toString());
        h.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        h.put(RequestHeaders.SESSION_ID, caller.sessionId().toString());
        h.put(RequestHeaders.SESSION_VERSION, "0");
        h.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        h.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        h.put(RequestHeaders.PERMISSIONS, String.join(",", caller.permissions()));
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return h;
    }
    private record Page(long total, List<Map<String, Object>> items) {}
    private static String text(Map<?, ?> row, String key) {
        Object value = row.get(key); return value == null ? null : value.toString();
    }
    private static List<Variant> variants(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        return rows.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(r -> new Variant(text(r, "id"), text(r, "specificationSnapshot"))).toList();
    }
}
