package com.rigour.order.infrastructure.integration;

import com.rigour.erp.api.v1.ErpProductManagementApi;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductManagementDetailView;
import com.rigour.erp.api.v1.model.ProductManagementSummaryView;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.Candidate;
import com.rigour.order.application.port.out.ErpOrderRepairCatalog;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 修复专用 ERP 只读适配器；不使用自动解析中的模糊匹配、默认SKU或评分优先。 */
public final class HttpErpOrderRepairCatalog implements ErpOrderRepairCatalog {
    private static final Logger log = LoggerFactory.getLogger(HttpErpOrderRepairCatalog.class);
    private static final String PRINCIPAL = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8)).toString();
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI base;

    public HttpErpOrderRepairCatalog(RestClient.Builder builder, TrustedContextSigner signer, String baseUrl) {
        this.client = builder.build();
        this.signer = signer;
        this.base = URI.create(baseUrl.replaceAll("/+$", ""));
        if (!List.of("https", "http").contains(base.getScheme())) throw new IllegalArgumentException("ERP服务地址无效");
    }

    @Override
    public List<Candidate> candidates(String tenantId, Query query) {
        UUID.fromString(tenantId);
        if (query.productCode() == null && (query.productName() == null || query.productName().isBlank())) return List.of();
        URI uri = UriComponentsBuilder.fromUri(base).path(ErpProductManagementApi.BASE_PATH)
                .queryParam("begin", 0).queryParam("step", 200)
                .queryParam(query.productCode() == null ? "productName" : "productCode",
                        query.productCode() == null ? query.productName() : query.productCode())
                .build().encode().toUri();
        MasterDataPageView<ProductManagementSummaryView> page = get(tenantId, uri, new ParameterizedTypeReference<>() { });
        // ERP 列表可能使用 LIKE；先证明结果完整，再在本地执行精确匹配。
        if (page.begin() != 0 || page.total() != page.items().size() || page.total() > 200) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "ERP查询结果过多或不完整，请提供更精确的商品编码", List.of());
        }
        List<Candidate> result = new ArrayList<>();
        for (var summary : page.items()) {
            if (!exactProduct(query, summary.productCode(), summary.productName())) continue;
            if (summary.id() == null || summary.id() <= 0) throw unavailable();
            URI detailUri = UriComponentsBuilder.fromUri(base).path(ErpProductManagementApi.BASE_PATH)
                    .pathSegment(summary.id().toString()).build().encode().toUri();
            ProductManagementDetailView product = get(tenantId, detailUri, new ParameterizedTypeReference<>() { });
            if (!summary.id().equals(product.id()) || !Objects.equals(summary.revision(), product.revision())
                    || !Objects.equals(summary.updatedTime(), product.updatedTime())
                    || !exactProduct(query, product.productCode(), product.productName())) {
                throw new BusinessException(ErrorCode.CONFLICT, "ERP商品查询期间发生变化，请重新预览", List.of());
            }
            for (var variant : product.variants()) {
                if (query.skuCode() != null && !query.skuCode().equals(variant.variantCode())) continue;
                if (query.skuCode() == null && query.specification() != null && !query.specification().isBlank()
                        && !query.specification().strip().equals(variant.specificationSnapshot())) continue;
                result.add(new Candidate(product.id(), variant.id(), product.productCode(), variant.variantCode(),
                        product.productName(), variant.specificationSnapshot(),
                        variant.unitCode() == null ? product.unitCode() : variant.unitCode(),
                        product.revision(), variant.revision(), product.updatedTime(), variant.updatedTime()));
            }
        }
        // 重复或跨商品SKU不折叠成单一候选，交由用例按歧义阻断。
        return List.copyOf(result);
    }

    private static boolean exactProduct(Query query, String code, String name) {
        return query.productCode() != null ? query.productCode().equals(code) : query.productName().strip().equals(name);
    }

    private <T> T get(String tenant, URI uri, ParameterizedTypeReference<ApiResponse<T>> type) {
        try {
            ApiResponse<T> response = client.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .headers(h -> headers(tenant, uri).forEach(h::set)).retrieve().body(type);
            if (response == null || !"OK".equals(response.code()) || response.data() == null) throw unavailable();
            return response.data();
        } catch (RestClientException exception) {
            log.warn("ERP商品复核读取失败 host={} path={} errorType={}",
                    uri.getHost(), uri.getPath(), exception.getClass().getSimpleName(), exception);
            throw unavailable();
        }
    }

    private Map<String, String> headers(String tenant, URI uri) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        headers.put(RequestHeaders.PRINCIPAL_ID, PRINCIPAL);
        headers.put(RequestHeaders.TENANT_ID, tenant);
        headers.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        headers.put(RequestHeaders.SESSION_VERSION, "0");
        headers.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        headers.put(RequestHeaders.PERMISSIONS, "erp:product:read");
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "ERP商品核验暂不可用，未应用修复", List.of());
    }
}
